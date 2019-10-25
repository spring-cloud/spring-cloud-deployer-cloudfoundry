/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.scheduler.cloudfoundry;

import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.net.ssl.SSLException;

import io.jsonwebtoken.lang.Assert;
import io.pivotal.scheduler.SchedulerClient;
import io.pivotal.scheduler.v1.jobs.CreateJobRequest;
import io.pivotal.scheduler.v1.jobs.DeleteJobRequest;
import io.pivotal.scheduler.v1.jobs.Job;
import io.pivotal.scheduler.v1.jobs.ListJobsRequest;
import io.pivotal.scheduler.v1.jobs.ListJobsResponse;
import io.pivotal.scheduler.v1.jobs.ScheduleJobRequest;
import io.pivotal.scheduler.v1.jobs.ScheduleJobResponse;
import io.pivotal.scheduler.v1.schedules.ExpressionType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.client.v2.applications.SummaryApplicationResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.AbstractApplicationSummary;
import org.cloudfoundry.operations.applications.ApplicationEnvironments;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.GetApplicationEnvironmentsRequest;
import org.cloudfoundry.operations.spaces.SpaceSummary;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryTaskLauncher;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.scheduler.CreateScheduleException;
import org.springframework.cloud.deployer.spi.scheduler.ScheduleInfo;
import org.springframework.cloud.deployer.spi.scheduler.ScheduleRequest;
import org.springframework.cloud.deployer.spi.scheduler.Scheduler;
import org.springframework.cloud.deployer.spi.scheduler.SchedulerException;
import org.springframework.cloud.deployer.spi.scheduler.SchedulerPropertyKeys;
import org.springframework.cloud.deployer.spi.scheduler.UnScheduleException;
import org.springframework.cloud.deployer.spi.scheduler.cloudfoundry.expression.QuartzCronExpression;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * A Cloud Foundry implementation of the Scheduler interface.
 *
 * @author Glenn Renfro
 */
public class CloudFoundryAppScheduler implements Scheduler {

	private final static int PCF_PAGE_START_NUM = 1; //First PageNum for PCFScheduler starts at 1.

	private final static String SCHEDULER_SERVICE_ERROR_MESSAGE = "Scheduler Service returned a null response.";

	private final static String SCHEDULER_TASK_DEF_NAME_KEY = "spring-task-definition-name";

	protected final static Log logger = LogFactory.getLog(CloudFoundryAppScheduler.class);
	private final SchedulerClient client;
	private final CloudFoundryOperations operations;
	private final CloudFoundryConnectionProperties properties;
	private final CloudFoundryTaskLauncher taskLauncher;
	private final CloudFoundrySchedulerProperties schedulerProperties;
	private final Map<String, String> scheduleTaskMap;

	public CloudFoundryAppScheduler(SchedulerClient client, CloudFoundryOperations operations,
			CloudFoundryConnectionProperties properties, CloudFoundryTaskLauncher taskLauncher,
			CloudFoundrySchedulerProperties schedulerProperties) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(operations, "operations must not be null");
		Assert.notNull(properties, "properties must not be null");
		Assert.notNull(taskLauncher, "taskLauncher must not be null");
		Assert.notNull(schedulerProperties, "schedulerProperties must not be null");

		this.client = client;
		this.operations = operations;
		this.properties = properties;
		this.taskLauncher = taskLauncher;
		this.schedulerProperties = schedulerProperties;
		this.scheduleTaskMap = new HashMap<>();
	}

	@Override
	public void schedule(ScheduleRequest scheduleRequest) {
		String appName = scheduleRequest.getDefinition().getName();
		String scheduleName = scheduleRequest.getScheduleName();
		logger.debug(String.format("Scheduling: %s", scheduleName));

		String command = stageTask(scheduleRequest);

		String cronExpression = scheduleRequest.getSchedulerProperties().get(SchedulerPropertyKeys.CRON_EXPRESSION);
		Assert.hasText(cronExpression, String.format(
				"request's scheduleProperties must have a %s that is not null nor empty",
				SchedulerPropertyKeys.CRON_EXPRESSION));
		try {
			new QuartzCronExpression("0 " + cronExpression);
		}
		catch(ParseException pe) {
			throw new CreateScheduleException("Cron Expression is invalid: " + pe.getMessage(), pe);
		}
		retryTemplate().execute(new RetryCallback<Void, RuntimeException>() {
					@Override
					public Void doWithRetry(RetryContext retryContext) throws RuntimeException {
						scheduleTask(appName, scheduleName, cronExpression, command);
						return null;
					}
				},
				new RecoveryCallback<Void>() {
					@Override
					public Void recover(RetryContext retryContext) throws Exception {
						if (retryContext.getLastThrowable() != null) {
							logger.error("Retry Context reported the following exception: " + retryContext.getLastThrowable().getMessage());
						}
						logger.error("Unable to schedule application");
						try {
							logger.debug("removing job portion of the schedule.");
							unschedule(scheduleName);
						}
						catch (UnScheduleException ex) {
							logger.debug("No job to be removed.");
						}
						throw new CreateScheduleException(scheduleName, retryContext.getLastThrowable());
					}
				});
	}

	@Override
	public void unschedule(String scheduleName) {
		logger.debug(String.format("Unscheduling: %s", scheduleName));
		this.scheduleTaskMap.remove(scheduleName);
		this.client.jobs().delete(DeleteJobRequest.builder()
				.jobId(getJob(scheduleName))
				.build())
				.block(Duration.ofSeconds(schedulerProperties.getUnScheduleTimeoutInSeconds()));
	}

	@Override
	public List<ScheduleInfo> list(String taskDefinitionName) {
		return list().stream().filter(scheduleInfo ->
				scheduleInfo.getTaskDefinitionName().equals(taskDefinitionName))
				.collect(Collectors.toList());
	}

	@Override
	public List<ScheduleInfo> list() {
		List<ScheduleInfo> result = new ArrayList<>();
		for (int i = PCF_PAGE_START_NUM; i <= getJobPageCount(); i++) {
			List<ScheduleInfo> scheduleInfoPage = getSchedules(i)
					.collectList()
					.block(Duration.ofSeconds(schedulerProperties.getListTimeoutInSeconds()));
			if(scheduleInfoPage == null) {
				throw new SchedulerException(SCHEDULER_SERVICE_ERROR_MESSAGE);
			}
			result.addAll(scheduleInfoPage);
		}
		for(ScheduleInfo scheduleInfo : result) {
			if(! scheduleTaskMap.containsKey(scheduleInfo.getScheduleName())) {
				Mono<ApplicationEnvironments> appEnvMono = operations.applications().getEnvironments(GetApplicationEnvironmentsRequest.builder().name(scheduleInfo.getScheduleName()).build());
				String taskDefinitionNameFromEnvironment = appEnvMono.map(applicationEnvironments -> {
					Map<String, Object> appEnvs = applicationEnvironments.getUserProvided();
					ObjectMapper mapper = new ObjectMapper();
					String taskDefinitionName = null;
					try {
						Map<String, String> properties = mapper.readValue((String) appEnvs.get("SPRING_APPLICATION_JSON"), Map.class);
						if (properties.containsKey(SCHEDULER_TASK_DEF_NAME_KEY)) {
							taskDefinitionName = properties.get(SCHEDULER_TASK_DEF_NAME_KEY);
						}
					}
					catch (Exception jsonMappingException) {
						throw new IllegalArgumentException(jsonMappingException);
					}
					return taskDefinitionName;
				}).block();
				if (taskDefinitionNameFromEnvironment != null) {
					scheduleInfo.setTaskDefinitionName(taskDefinitionNameFromEnvironment);
					scheduleTaskMap.put(scheduleInfo.getScheduleName(), scheduleInfo.getTaskDefinitionName());
				}
			}
			else {
				scheduleInfo.setTaskDefinitionName(scheduleTaskMap.get(scheduleInfo.getScheduleName()));
			}
		}
		return result;
	}

	/**
	 * Schedules the job for the application.
	 * @param appName The name of the task app to be scheduled.
	 * @param scheduleName the name of the schedule.
	 * @param expression the cron expression.
	 * @param command the command returned from the staging.
	 */
	private void scheduleTask(String appName, String scheduleName,
			String expression, String command) {
		logger.debug(String.format("Scheduling Task: ", appName));
		ScheduleJobResponse response = getApplicationByAppName(scheduleName)
				.flatMap(abstractApplicationSummary -> {
					return this.client.jobs().create(CreateJobRequest.builder()
							.applicationId(abstractApplicationSummary.getId()) // App GUID
							.command(command)
							.name(scheduleName)
							.build());
				}).flatMap(createJobResponse -> {
			return this.client.jobs().schedule(ScheduleJobRequest.
					builder().
					jobId(createJobResponse.getId()).
					expression(expression).
					expressionType(ExpressionType.CRON).
					enabled(true).
					build());
		})
				.onErrorMap(e -> {
					if (e instanceof SSLException) {
						throw new CloudFoundryScheduleSSLException("Failed to schedule" + scheduleName, e);
					}
					else {
						throw new CreateScheduleException(scheduleName, e);
					}
				})
				.block(Duration.ofSeconds(schedulerProperties.getScheduleTimeoutInSeconds()));
		if(response == null) {
			throw new SchedulerException(SCHEDULER_SERVICE_ERROR_MESSAGE);
		}
	}

	/**
	 * Stages the application specified in the {@link ScheduleRequest} on the CF server.
	 * @param scheduleRequest {@link ScheduleRequest} containing the information required to schedule a task.
	 * @return the command string for the scheduled task.
	 */
	private String stageTask(ScheduleRequest scheduleRequest) {
		logger.debug(String.format("Staging Task: ",
				scheduleRequest.getDefinition().getName()));
		Map<String, String> properties = new HashMap<>(scheduleRequest.getDefinition().getProperties());
		properties.put(SCHEDULER_TASK_DEF_NAME_KEY,
				scheduleRequest.getDefinition().getName());
		this.scheduleTaskMap.put(scheduleRequest.getScheduleName(), scheduleRequest.getDefinition().getName());
		AppDefinition appDefinition = new AppDefinition(scheduleRequest.getScheduleName(), properties);

		AppDeploymentRequest request = new AppDeploymentRequest(
				appDefinition,
				scheduleRequest.getResource(),
				scheduleRequest.getDeploymentProperties(),
				scheduleRequest.getCommandlineArguments());
		SummaryApplicationResponse response = taskLauncher.stage(request);
		return taskLauncher.getCommand(response, request);
	}

	/**
	 * Retrieve a {@link Mono} containing the {@link ApplicationSummary} associated with the appId.
	 * @param appName the name of the {@link AbstractApplicationSummary} to search.
	 */
	private Mono<AbstractApplicationSummary> getApplicationByAppName(String appName) {
		return requestListApplications()
				.filter(application -> appName.equals(application.getName()))
				.singleOrEmpty()
				.cast(AbstractApplicationSummary.class);
	}

	/**
	 * Retrieve a  {@link Flux} of {@link ApplicationSummary}s.
	 */
	private Flux<ApplicationSummary> requestListApplications() {
		return this.operations.applications()
				.list();
	}

	/**
	 * Retrieve a cached {@link Flux} of {@link ApplicationSummary}s.
	 */
	private Flux<ApplicationSummary> cacheAppSummaries() {
		return requestListApplications()
				.cache(); //cache results from first call.  No need to re-retrieve each time.
	}

	/**
	 * Retrieve a {@link Flux} containing the available {@link SpaceSummary}s.
	 * @return {@link Flux} of {@link SpaceSummary}s.
	 */
	private Flux<SpaceSummary> requestSpaces() {
		return this.operations.spaces()
				.list();
	}

	/**
	 * Retrieve a {@link Mono} containing a {@link SpaceSummary} for the specified name.
	 * @param spaceName the name of space to search.
	 * @return the {@link SpaceSummary} associated with the spaceName.
	 */
	private Mono<SpaceSummary> getSpace(String spaceName) {
		return requestSpaces()
				.cache() //cache results from first call.
				.filter(space -> spaceName.equals(space.getName()))
				.singleOrEmpty()
				.cast(SpaceSummary.class);
	}

	/**
	 * Retrieve a {@link Mono} containing the {@link ApplicationSummary} associated with the appId.
	 * @param applicationSummaries {@link Flux} of {@link ApplicationSummary}s to filter.
	 * @param appId the id of the {@link ApplicationSummary} to search.
	 */
	private Mono<ApplicationSummary> getApplication(Flux<ApplicationSummary> applicationSummaries,
			String appId) {
		return applicationSummaries
				.filter(application -> appId.equals(application.getId()))
				.singleOrEmpty();
	}

	/**
	 * Retrieve a Flux of {@link ScheduleInfo}s for the pageNumber specified.
	 * The PCF-Scheduler returns all data in pages of 50 entries.  This method
	 * retrieves the specified page and transforms the {@link Flux} of {@link Job}s to
	 * a {@link Flux} of {@link ScheduleInfo}s
	 *
	 * @param pageNumber integer containing the page offset for the {@link ScheduleInfo}s to retrieve.
	 * @return {@link Flux} containing the {@link ScheduleInfo}s for the specified page number.
	 */
	private Flux<ScheduleInfo> getSchedules(int pageNumber) {
		Flux<ApplicationSummary> applicationSummaries = cacheAppSummaries();
		return this.getSpace(this.properties.getSpace()).flatMap(requestSummary -> {
			return this.client.jobs().list(ListJobsRequest.builder()
					.spaceId(requestSummary.getId())
					.page(pageNumber)
					.detailed(true).build());})
				.flatMapIterable(jobs -> jobs.getResources())// iterate over the resources returned.
				.flatMap(job -> {
					return getApplication(applicationSummaries,
							job.getApplicationId()) // get the application name for each job.
							.map(optionalApp -> {
								ScheduleInfo scheduleInfo = new ScheduleInfo();
								scheduleInfo.setScheduleProperties(new HashMap<>());
								scheduleInfo.setScheduleName(job.getName());
								scheduleInfo.setTaskDefinitionName(optionalApp.getName());
								if (job.getJobSchedules() != null) {
									scheduleInfo.getScheduleProperties().put(SchedulerPropertyKeys.CRON_EXPRESSION,
											job.getJobSchedules().get(0).getExpression());
								}
								else {
									logger.warn(String.format("Job %s does not have an associated schedule", job.getName()));
								}
								return scheduleInfo;
							});
				});
	}

	/**
	 * Retrieves the number of pages that can be returned when retrieving a list of jobs.
	 * @return an int containing the number of available pages.
	 */
	private int getJobPageCount() {
		ListJobsResponse response = this.getSpace(this.properties.getSpace()).flatMap(requestSummary -> {
			return this.client.jobs().list(ListJobsRequest.builder()
					.spaceId(requestSummary.getId())
					.detailed(false).build());
		}).block();
		if(response == null) {
			throw new SchedulerException(SCHEDULER_SERVICE_ERROR_MESSAGE);
		}
		return response.getPagination().getTotalPages();
	}

	/**
	 * Retrieve a {@link Mono} that contains the {@link Job} for the jobName or null.
	 * @param jobName - the name of the job to search search.
	 * @param page - the page to search.
	 * @return {@link Mono} containing the {@link Job} if found or null if not found.
	 */
	private Mono<Job> getJobMono(String jobName, int page) {
		return this.getSpace(this.properties.getSpace()).flatMap(requestSummary -> {
			return this.client
					.jobs()
					.list(ListJobsRequest.builder()
							.spaceId(requestSummary.getId())
							.page(page)
							.build()); })
				.flatMapIterable(jobs -> jobs.getResources())
				.filter(job -> job.getName().equals(jobName))
				.singleOrEmpty();// iterate over the resources returned.
	}

	/**
	 * Retrieve the job id for the specified PCF Job Name.
	 * @param jobName the name of the job to search.
	 * @return The job id associated with the job.
	 */
	private String getJob(String jobName) {
		Job result = null;
		final int pageCount = getJobPageCount();
		for (int pageNum = PCF_PAGE_START_NUM; pageNum <= pageCount; pageNum++) {
			result = getJobMono(jobName, pageNum)
					.block();
			if (result != null) {
				break;
			}
		}
		if(result == null) {
			throw new UnScheduleException(String.format("schedule %s does not exist.", jobName));
		}
		return result.getId();
	}

	private RetryTemplate retryTemplate() {
		RetryTemplate retryTemplate = new RetryTemplate();
		SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
				schedulerProperties.getScheduleSSLRetryCount(),
				Collections.singletonMap(CloudFoundryScheduleSSLException.class, true));
		retryTemplate.setRetryPolicy(retryPolicy);
		return retryTemplate;
	}
}
