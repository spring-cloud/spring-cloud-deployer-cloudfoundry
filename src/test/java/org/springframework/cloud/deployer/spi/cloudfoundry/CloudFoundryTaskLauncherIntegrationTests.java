/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.cloudfoundry;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.servicebindings.DeleteServiceBindingRequest;
import org.cloudfoundry.client.v3.servicebindings.ListServiceBindingsRequest;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.util.PaginationUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Runs integration tests for {@link CloudFoundryTaskLauncher}, using the production configuration,
 * that may be configured via {@link CloudFoundryDeployerProperties}.
 *
 * Tests are only run if a successful connection can be made at startup.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 * @author Michael Minella
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = CloudFoundryDeployerProperties.class)
@IntegrationTest
public class CloudFoundryTaskLauncherIntegrationTests {

	private CloudFoundryTaskLauncher taskLauncher;

	@Autowired
	ApplicationContext context;

	@Autowired
	CloudFoundryDeployerProperties properties;

	AppDeploymentRequest request;

	/**
	 * Execution environments may override this default value to have tests wait longer for a deployment, for example if
	 * running in an environment that is known to be slow.
	 */
	protected double timeoutMultiplier = 1.0D;

	@Before
	public void init() {
		String multiplier = System.getenv("CF_DEPLOYER_TIMEOUT_MULTIPLIER");
		if (multiplier != null) {
			timeoutMultiplier = Double.parseDouble(multiplier);
		}

		Map<String, String> envProperties = new HashMap<>();
		envProperties.put("organization", "spring");
		envProperties.put("space", "development");
		envProperties.put(CloudFoundryDeployerProperties.SERVICES_PROPERTY_KEY, "my_mysql");
		envProperties.put(CloudFoundryDeployerProperties.MEMORY_PROPERTY_KEY, "1024");
		envProperties.put(CloudFoundryDeployerProperties.DISK_PROPERTY_KEY, "2048");

		List<String> commandLineArgs = new ArrayList<>(3);
		commandLineArgs.add("--foo=bar");
		commandLineArgs.add("--baz=qux");
		commandLineArgs.add("random=" + UUID.randomUUID().toString());

		request = new AppDeploymentRequest(
			new AppDefinition("timestamp", Collections.emptyMap()),
			context.getResource("classpath:batch-job-1.0.0.BUILD-SNAPSHOT.jar"),
			envProperties,
			commandLineArgs);

		CloudFoundryOperations cloudFoundryOperations = DefaultCloudFoundryOperations.builder()
			.cloudFoundryClient(cfAvailable.getResource())
			.organization("spring")
			.space("development")
			.build();

		taskLauncher = new CloudFoundryTaskLauncher(cfAvailable.getResource(), cloudFoundryOperations, properties);
	}

	@Test
	public void testNonExistentAppsStatus() {
		assertThat(taskLauncher.status("foo").getState(), is(LaunchState.unknown));
	}

	@Test
	public void testSimpleLaunch() throws InterruptedException {

		String taskId = taskLauncher.asyncLaunch(request).block(Duration.of(5, ChronoUnit.MINUTES));

		System.out.println(">> taskId = " + taskId);

		TaskStatus status = taskLauncher.asyncStatus(taskId).block();

		while (!status.getState().equals(LaunchState.complete)) {
			System.out.println(">> state = " + status.getState());
			Thread.sleep(5000);
			status = taskLauncher.asyncStatus(taskId).block();
		}

		assertThat(status.getState(), is(LaunchState.complete));
	}

	@Test
	public void testSimpleCancel() throws InterruptedException {
		Map<String, String> envProperties = new HashMap<>();
		envProperties.put("organization", "spring");
		envProperties.put("space", "development");
		envProperties.put(CloudFoundryDeployerProperties.SERVICES_PROPERTY_KEY, "my_mysql");
		envProperties.put(CloudFoundryDeployerProperties.MEMORY_PROPERTY_KEY, "1024");
		envProperties.put(CloudFoundryDeployerProperties.DISK_PROPERTY_KEY, "2048");

		List<String> commandLineArgs = new ArrayList<>(2);
		commandLineArgs.add("30000");

		request = new AppDeploymentRequest(
			new AppDefinition("long-runner", Collections.emptyMap()),
			context.getResource("classpath:long-running-task-1.0.0.BUILD-SNAPSHOT.jar"),
			envProperties,
			commandLineArgs);

		String taskId = taskLauncher.asyncLaunch(request).block(Duration.of(5, ChronoUnit.MINUTES));

		System.out.println(">> taskId = " + taskId);

		Thread.sleep(10000L);

		System.out.println(">> About to cancel the task");

		taskLauncher.cancel(taskId);

		TaskStatus status = taskLauncher.asyncStatus(taskId).block();

		while (!status.getState().equals(LaunchState.failed)) {
			System.out.println(">> state = " + status.getState());
			Thread.sleep(5000);
			status = taskLauncher.asyncStatus(taskId).block();
		}

		assertThat(status.getState(), is(LaunchState.failed));
	}

	@After
	public void cleanUp() throws InterruptedException {
		CloudFoundryClient client = cfAvailable.getResource();

		unbindServices(client, "timestamp");
		unbindServices(client, "long-runner");
	}

	private void unbindServices(CloudFoundryClient client, String applicationName) {
		PaginationUtils.requestClientV3Resources(page -> client.applicationsV3().list(ListApplicationsRequest.builder()
				.name(applicationName)
				.page(page)
				.build()))
			.log("applicationResponses")
			.singleOrEmpty()
			.log("single")
			.then(applicationResource -> client.serviceBindingsV3().list(ListServiceBindingsRequest.builder()
				.applicationId(applicationResource.getId())
				.build()))
			.log("serviceBindingRequest")
			.flatMap(serviceBindingsResponse -> Flux.fromIterable(serviceBindingsResponse.getResources()))
			.log("serviceBindingResponses")
			.flatMap(serviceBindingResource -> client.serviceBindingsV3().delete(DeleteServiceBindingRequest.builder()
				.serviceBindingId(serviceBindingResource.getId())
				.build()))
			.log("serviceBindingDeletes")
			.singleOrEmpty()
			.block();
	}

	/**
	 * Return the timeout to use for repeatedly querying a module while it is being deployed.
	 * Default value is one minute, being queried every 5 seconds.
	 */
	protected Attempts deploymentTimeout() {
		return new Attempts(12, (int) (5000 * timeoutMultiplier));
	}

	/**
	 * Return the timeout to use for repeatedly querying a module while it is being un-deployed.
	 * Default value is one minute, being queried every 5 seconds.
	 */
	protected Attempts undeploymentTimeout() {
		return new Attempts(20, (int) (5000 * timeoutMultiplier));
	}


	/**
	 * Represents a timeout for querying status, with repetitive queries until a certain number have been made.
	 * @author Eric Bottard
	 */
	protected static class Attempts {

		public final int noAttempts;

		public final int pause;

		public Attempts(int noAttempts, int pause) {
			this.noAttempts = noAttempts;
			this.pause = pause;
		}
	}

	@ClassRule
	public static CloudFoundryTestSupport cfAvailable = new CloudFoundryTestSupport();

}
