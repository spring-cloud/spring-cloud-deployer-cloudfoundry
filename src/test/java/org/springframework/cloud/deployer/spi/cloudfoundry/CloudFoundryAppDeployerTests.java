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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.beans.HasPropertyWithValue.hasProperty;
import static org.junit.rules.ExpectedException.none;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.DOMAIN_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HOST_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.ROUTE_PATH_PROPERTY;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.ApplicationsV2;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UpdateApplicationResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.cloudfoundry.operations.services.Services;
import org.cloudfoundry.util.test.TestSubscriber;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Unit tests for the {@link CloudFoundryAppDeployer}.
 *
 * @author Greg Turnquist
 * @author Eric Bottard
 */
public class CloudFoundryAppDeployerTests {

	@Rule
	public ExpectedException thrown = none();

	private CloudFoundryOperations operations;

	private CloudFoundryClient client;

	private Applications applications;

	private ApplicationsV2 applicationsV2;

	private Services services;

	private CloudFoundryAppDeployer deployer;

	private AppNameGenerator deploymentCustomizer;

	private CloudFoundryDeploymentProperties cloudFoundryDeploymentProperties = new CloudFoundryDeploymentProperties();

	@Before
	public void setUp() throws Exception {

		operations = mock(CloudFoundryOperations.class);
		client = mock(CloudFoundryClient.class);
		applications = mock(Applications.class);
		applicationsV2 = mock(ApplicationsV2.class);
		services = mock(Services.class);


		cloudFoundryDeploymentProperties.setAppNamePrefix("dataflow-server");
		//Tests are setup not to handle random name prefix = true;
		cloudFoundryDeploymentProperties.setEnableRandomAppNamePrefix(false);
		cloudFoundryDeploymentProperties.setServices(new HashSet<>(Arrays.asList("redis-service", "mysql-service")));

		deploymentCustomizer = new CloudFoundryAppNameGenerator(cloudFoundryDeploymentProperties, new WordListRandomWords());
		((CloudFoundryAppNameGenerator)deploymentCustomizer).afterPropertiesSet();

		deployer = new CloudFoundryAppDeployer(new CloudFoundryConnectionProperties(), cloudFoundryDeploymentProperties, operations,
				client, deploymentCustomizer);
	}

	@Test
	public void shouldHonorRouteCustomization() throws Exception {
		FileSystemResource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		String deploymentId = runFullDeployment(new AppDeploymentRequest(
				new AppDefinition("time", Collections.emptyMap()),
				resource,
				Collections.singletonMap(AppDeployer.GROUP_PROPERTY_KEY, "ticktock")));



	}

	@Test
	public void shouldNamespaceTheDeploymentIdWhenAGroupIsUsed() throws InterruptedException, IOException {
		FileSystemResource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		String deploymentId = runFullDeployment(new AppDeploymentRequest(
				new AppDefinition("time", Collections.emptyMap()),
				resource,
				Collections.singletonMap(AppDeployer.GROUP_PROPERTY_KEY, "ticktock")));

		// then
		assertThat(deploymentId, equalTo("dataflow-server-ticktock-time"));
	}

	@Test
	public void shouldNotNamespaceTheDeploymentIdWhenNoGroupIsUsed() throws InterruptedException, IOException {
		FileSystemResource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		cloudFoundryDeploymentProperties.setDomain("wizz.com");
		cloudFoundryDeploymentProperties.setHost("quik");

		runFullDeployment(new AppDeploymentRequest(
				new AppDefinition("time", Collections.emptyMap()),
				resource,
				Collections.emptyMap()));

		verify(applications).push(argThat(hasProperty("host", is("quik"))));
		verify(applications).push(argThat(hasProperty("domain", is("wizz.com"))));


		// Test per-app overrides
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put(HOST_PROPERTY, "foo");
		deploymentProperties.put(DOMAIN_PROPERTY, "bar.com");
		deploymentProperties.put(ROUTE_PATH_PROPERTY, "/sub-route");

		runFullDeployment(new AppDeploymentRequest(
				new AppDefinition("time", Collections.emptyMap()),
				resource,
				deploymentProperties));

		verify(applications).push(argThat(hasProperty("host", is("foo"))));
		verify(applications).push(argThat(hasProperty("domain", is("bar.com"))));
		verify(applications).push(argThat(hasProperty("routePath", is("/sub-route"))));
	}

	@Test
	public void moveAppPropertiesToSAJ() throws InterruptedException, IOException {

		// given
		CloudFoundryConnectionProperties properties = new CloudFoundryConnectionProperties();
		CloudFoundryDeploymentProperties deploymentProperties = new CloudFoundryDeploymentProperties();

		// Define the deployment properties for the app
		Map<String, String> appDeploymentProperties = new HashMap<>();

		final String fooKey = "spring.cloud.foo";
		final String fooVal = "this should NOT end up in SPRING_APPLICATION_JSON";

		final String barKey = "another.cloud.bar";
		final String barVal = "neither should";

		appDeploymentProperties.put(fooKey, fooVal);
		appDeploymentProperties.put(barKey, barVal);

		deployer = new CloudFoundryAppDeployer(properties, deploymentProperties, operations, client, deploymentCustomizer);

		given(operations.applications()).willReturn(applications);

		mockGetApplication("test", "RUNNING");
		given(applications.push(any())).willReturn(Mono.empty());
		given(applications.start(any())).willReturn(Mono.empty());

		given(client.applicationsV2()).willReturn(applicationsV2);

		given(applicationsV2.update(any())).willReturn(Mono.just(UpdateApplicationResponse.builder()
				.build()));

		// when
		final TestSubscriber<Void> testSubscriber = new TestSubscriber<>();
		FileSystemResource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		deployer.asyncDeploy(new AppDeploymentRequest(
				new AppDefinition("test", Collections.singletonMap("some.key", "someValue")),
				resource,
				appDeploymentProperties))
				.subscribe(testSubscriber);

		testSubscriber.verify(Duration.ofSeconds(10L));

		// then
		then(operations).should(times(3)).applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().push(any());
		then(applications).should().get(any());
		then(applications).should().start(any());
		verifyNoMoreInteractions(applications);

		then(client).should().applicationsV2();
		verifyNoMoreInteractions(client);

		then(applicationsV2).should().update(UpdateApplicationRequest.builder()
				.applicationId("abc123")
				.environmentJsons(new HashMap<String, String>() {{
					put("SPRING_APPLICATION_JSON",
							new ObjectMapper().writeValueAsString(
									Collections.singletonMap("some.key", "someValue")));
					// Note that fooKey and barKey are not expected to be in the environment as they are
					// deployment properties
				}})
				.build());
		verifyNoMoreInteractions(applicationsV2);
	}

	@Test
	public void applyAppPropertiesIndividually() throws InterruptedException, IOException {

		// given
		CloudFoundryDeploymentProperties deploymentProperties = new CloudFoundryDeploymentProperties();
		CloudFoundryConnectionProperties connProperties = new CloudFoundryConnectionProperties();

		// Define the deployment properties for the app
		Map<String, String> appDeploymentProperties = new HashMap<>();

		final String fooKey = "spring.cloud.foo";
		final String fooVal = "this should NOT end up in SPRING_APPLICATION_JSON";

		final String barKey = "another.cloud.bar";
		final String barVal = "neither should this";

		appDeploymentProperties.put(fooKey, fooVal);
		appDeploymentProperties.put(barKey, barVal);

		deployer = new CloudFoundryAppDeployer(connProperties, deploymentProperties, operations, client, deploymentCustomizer);

		given(operations.applications()).willReturn(applications);

		mockGetApplication("test", "RUNNING");
		given(applications.push(any())).willReturn(Mono.empty());
		given(applications.start(any())).willReturn(Mono.empty());

		given(client.applicationsV2()).willReturn(applicationsV2);

		given(applicationsV2.update(any())).willReturn(Mono.just(UpdateApplicationResponse.builder()
				.build()));

		// when
		final TestSubscriber<Void> testSubscriber = new TestSubscriber<>();
		FileSystemResource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		appDeploymentProperties.put(CloudFoundryDeploymentProperties.USE_SPRING_APPLICATION_JSON_KEY, "false");

		deployer.asyncDeploy(new AppDeploymentRequest(
				new AppDefinition("test", Collections.singletonMap("some.key", "someValue")),
				resource,
				appDeploymentProperties))
				.subscribe(testSubscriber);

		testSubscriber.verify(Duration.ofSeconds(10L));

		// then
		then(operations).should(times(3)).applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().push(any());
		then(applications).should().get(any());
		then(applications).should().start(any());
		verifyNoMoreInteractions(applications);

		then(client).should().applicationsV2();
		verifyNoMoreInteractions(client);

		then(applicationsV2).should().update(UpdateApplicationRequest.builder()
				.applicationId("abc123")
				.environmentJsons(new LinkedHashMap<String, String>() {{
					put("some.key", "someValue");
					// Note that fooKey and barKey are not expected to be in the environment as they are
					// deployment properties
				}})
				.build());
		verifyNoMoreInteractions(applicationsV2);
	}

	@Test
	public void shouldHandleRoutineDeployment() throws InterruptedException, IOException {
		FileSystemResource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		runAsyncFullDeployment(new AppDeploymentRequest(
				new AppDefinition("time", Collections.emptyMap()),
				resource,
				Collections.emptyMap()));
		// then
		then(operations).should(times(3)).applications();
		then(operations).should(times(2)).services();
		verifyNoMoreInteractions(operations);

		then(applications).should().push(any());
		then(applications).should().get(GetApplicationRequest.builder().name("dataflow-server-time").build());
		then(applications).should().start(StartApplicationRequest.builder().name("dataflow-server-time").build());
		verifyNoMoreInteractions(applications);

		then(client).should().applicationsV2();
		verifyNoMoreInteractions(client);

		then(applicationsV2).should().update(UpdateApplicationRequest.builder()
				.applicationId("abc123")
				.environmentJsons(new HashMap<String, String>() {{
					put("SPRING_APPLICATION_JSON", "{}");
				}})
				.build());
		verifyNoMoreInteractions(applicationsV2);

		then(services).should().bind(BindServiceInstanceRequest.builder()
				.applicationName("dataflow-server-time")
				.serviceInstanceName("redis-service")
				.build());
		then(services).should().bind(BindServiceInstanceRequest.builder()
				.applicationName("dataflow-server-time")
				.serviceInstanceName("mysql-service")
				.build());
		verifyNoMoreInteractions(services);
	}

	@Test
	public void shouldFailWhenDeployingSameAppTwice() throws InterruptedException, IllegalStateException {

		// given
		given(operations.applications()).willReturn(applications);

		mockGetApplicationWithInstanceDetail("test", "RUNNING", "RUNNING");

		thrown.expect(IllegalStateException.class);
		thrown.expectMessage(containsString("already deployed"));

		// when
		deployer.deploy(new AppDeploymentRequest(
				new AppDefinition("test", Collections.emptyMap()),
				mock(Resource.class),
				Collections.emptyMap()));

		// then
		// JUnit's exception handlers must be before the actual code is run
	}

	@Test
	public void shouldHandleRoutineUndeployment() throws InterruptedException, JsonProcessingException {

		// given
		given(operations.applications()).willReturn(applications);
		given(applications.delete(any())).willReturn(Mono.empty());

		// when
		final TestSubscriber<Void> testSubscriber = new TestSubscriber<>();

		deployer.asyncUndeploy("test")
				.subscribe(testSubscriber);

		testSubscriber.verify(Duration.ofSeconds(10L));

		// then
		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().delete(DeleteApplicationRequest.builder()
				.name("test")
				.deleteRoutes(true)
				.build());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportDownStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		mockGetApplicationWithInstanceDetail("test", "DOWN", "DOWN");

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.deploying));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}


	@Test
	public void shouldReportStartingStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		mockGetApplicationWithInstanceDetail("test", "RUNNING", "STARTING");

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.deploying));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportCrashedStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		mockGetApplicationWithInstanceDetail("test", "RUNNING", "CRASHED");

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.failed));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportFlappingStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		mockGetApplicationWithInstanceDetail("test", "RUNNING", "FLAPPING");

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.deployed));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportRunningStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		mockGetApplicationWithInstanceDetail("test", "RUNNING", "RUNNING");

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.deployed));
		assertThat(status.getInstances().get("test-0").toString(), equalTo("CloudFoundryAppInstanceStatus[test-0 : deployed]"));
		assertThat(status.getInstances().get("test-0").getAttributes(), equalTo(Collections.emptyMap()));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportUnknownStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		mockGetApplicationWithInstanceDetail("test", "RUNNING", "UNKNOWN");

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.unknown));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldErrorOnUnknownState() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		mockGetApplicationWithInstanceDetail("test", "RUNNING", "some code never before seen");

		thrown.expect(IllegalStateException.class);
		thrown.expectMessage(containsString("Unsupported CF state"));

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.unknown));
	}

	private void mockGetApplication(String applicationName, String requestedStatus) {
		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name(applicationName)
				.stack("stack")
				.diskQuota(1024)
				.instances(1)
				.memoryLimit(1024)
				.requestedState(requestedStatus)
				.runningInstances(1)
				.build()));
	}

	private void mockGetApplicationWithInstanceDetail(String applicationName, String requestedState, String instanceState) {
		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name(applicationName)
				.stack("stack")
				.diskQuota(1024)
				.instances(1)
				.memoryLimit(1024)
				.requestedState(requestedState)
				.runningInstances(1)
				.instanceDetail(InstanceDetail.builder()
						.state(instanceState)
						.build())
				.build()));
	}

	private void runAsyncFullDeployment(AppDeploymentRequest request) throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);
		given(operations.services()).willReturn(services);

		mockGetApplication(request.getDefinition().getName(), "RUNNING");
		given(applications.push(any())).willReturn(Mono.empty());
		given(applications.start(any())).willReturn(Mono.empty());

		given(client.applicationsV2()).willReturn(applicationsV2);

		given(applicationsV2.update(any())).willReturn(Mono.just(UpdateApplicationResponse.builder()
				.build()));

		given(services.bind(any())).willReturn(Mono.empty());

		// when
		final TestSubscriber<Void> testSubscriber = new TestSubscriber<>();

		deployer.asyncDeploy(request)
				.subscribe(testSubscriber);

		testSubscriber.verify(Duration.ofSeconds(10L));
	}

	private String runFullDeployment(AppDeploymentRequest request) throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);
		given(operations.services()).willReturn(services);

		mockGetUnknownApplication();
		given(applications.push(any())).willReturn(Mono.empty());
		given(applications.start(any())).willReturn(Mono.empty());

		given(client.applicationsV2()).willReturn(applicationsV2);

		given(applicationsV2.update(any())).willReturn(Mono.just(UpdateApplicationResponse.builder()
				.build()));

		given(services.bind(any())).willReturn(Mono.empty());

		// when
		return deployer.deploy(request);
	}

	private void mockGetUnknownApplication() {
		given(applications.get(any())).willReturn(Mono.error(new RuntimeException()));
	}

}
