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
import static org.mockito.BDDMockito.given;
import static org.springframework.cloud.deployer.spi.app.AppDeployer.COUNT_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.app.AppDeployer.GROUP_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.BUILDPACK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.DOMAIN_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HEALTHCHECK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HOST_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.NO_ROUTE_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.ROUTE_PATH_PROPERTY;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.operations.applications.Route;
import org.cloudfoundry.operations.services.Services;
import org.cloudfoundry.util.FluentMap;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Unit tests for the {@link CloudFoundryAppDeployer}.
 *
 * @author Greg Turnquist
 * @author Eric Bottard
 * @author Ilayaperumal Gopinathan
 * @author Ben Hale
 */
public class CloudFoundryAppDeployerTests {

	private final CloudFoundryDeploymentProperties deploymentProperties = new CloudFoundryDeploymentProperties();

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private AppNameGenerator applicationNameGenerator;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Applications applications;

	private CloudFoundryAppDeployer deployer;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryOperations operations;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Services services;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private RuntimeEnvironmentInfo runtimeEnvironmentInfo;


	@Before
	public void setUp() {
		MockitoAnnotations.initMocks(this);
		given(this.operations.applications()).willReturn(this.applications);
		given(this.operations.services()).willReturn(this.services);

		this.deploymentProperties.setServices(new HashSet<>(Arrays.asList("test-service-1", "test-service-2")));

		this.deployer = new CloudFoundryAppDeployer(this.applicationNameGenerator, this.deploymentProperties,
			this.operations, this.runtimeEnvironmentInfo);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deploy() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack(deploymentProperties.getBuildpack())
				.disk(1024)
				.environmentVariables(defaultEnvironmentVariables())
				.instances(1)
				.memory(1024)
				.name("test-application-id")
				.service("test-service-2")
				.service("test-service-1")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			Collections.emptyMap()));

		assertThat(deploymentId, equalTo("test-application-id"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithAdditionalProperties() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		Map<String, String> environmentVariables = new HashMap<>();
		environmentVariables.put("test-key-1", "test-value-1");
		addGuidAndIndex(environmentVariables);

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack(deploymentProperties.getBuildpack())
				.disk(1024)
				.environmentVariables(environmentVariables)
				.instances(1)
				.memory(1024)
				.name("test-application-id")
				.service("test-service-2")
				.service("test-service-1")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.singletonMap("test-key-1", "test-value-1")),
			resource,
			FluentMap.<String, String>builder()
				.entry("test-key-2", "test-value-2")
				.entry(CloudFoundryDeploymentProperties.USE_SPRING_APPLICATION_JSON_KEY, String.valueOf(false))
				.build()));

		assertThat(deploymentId, equalTo("test-application-id"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithAdditionalPropertiesInSpringApplicationJson() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		Map<String, String> environmentVariables = new HashMap<>();
		environmentVariables.put("SPRING_APPLICATION_JSON", "{\"test-key-1\":\"test-value-1\"}");
		addGuidAndIndex(environmentVariables);

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack(deploymentProperties.getBuildpack())
				.disk(1024)
				.environmentVariables(environmentVariables)
				.instances(1)
				.memory(1024)
				.name("test-application-id")
				.service("test-service-2")
				.service("test-service-1")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.singletonMap("test-key-1", "test-value-1")),
			resource,
			Collections.singletonMap("test-key-2", "test-value-2")));

		assertThat(deploymentId, equalTo("test-application-id"));
	}


	@SuppressWarnings("unchecked")
	@Test
	public void deployWithApplicationDeploymentProperties() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack("test-buildpack")
				.disk(0)
				.environmentVariables(defaultEnvironmentVariables())
				.healthCheckType(ApplicationHealthCheck.NONE)
				.instances(0)
				.memory(0)
				.name("test-application-id")
				.noRoute(false)
				.host("test-host")
				.domain("test-domain")
				.routePath("/test-route-path")
				.service("test-service-2")
				.service("test-service-1")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			FluentMap.<String, String>builder()
				.entry(BUILDPACK_PROPERTY_KEY, "test-buildpack")
				.entry(AppDeployer.DISK_PROPERTY_KEY, "0")
				.entry(DOMAIN_PROPERTY, "test-domain")
				.entry(HEALTHCHECK_PROPERTY_KEY, "none")
				.entry(HOST_PROPERTY, "test-host")
				.entry(COUNT_PROPERTY_KEY, "0")
				.entry(AppDeployer.MEMORY_PROPERTY_KEY, "0")
				.entry(NO_ROUTE_PROPERTY, "false")
				.entry(ROUTE_PATH_PROPERTY, "/test-route-path")
				.build()));

		assertThat(deploymentId, equalTo("test-application-id"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithCustomDeploymentProperties() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		this.deploymentProperties.setBuildpack("test-buildpack");
		this.deploymentProperties.setDisk("0");
		this.deploymentProperties.setDomain("test-domain");
		this.deploymentProperties.setHealthCheck(ApplicationHealthCheck.NONE);
		this.deploymentProperties.setHost("test-host");
		this.deploymentProperties.setInstances(0);
		this.deploymentProperties.setMemory("0");

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack("test-buildpack")
				.disk(0)
				.domain("test-domain")
				.environmentVariables(defaultEnvironmentVariables())
				.healthCheckType(ApplicationHealthCheck.NONE)
				.host("test-host")
				.instances(0)
				.memory(0)
				.name("test-application-id")
				.service("test-service-2")
				.service("test-service-1")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			Collections.emptyMap()));

		assertThat(deploymentId, equalTo("test-application-id"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithGroup() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-group-test-application")).willReturn("test-group-test-application-id");

		givenRequestGetApplication("test-group-test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-group-test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-group-test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack(deploymentProperties.getBuildpack())
				.disk(1024)
				.environmentVariable("SPRING_CLOUD_APPLICATION_GROUP", "test-group")
				.environmentVariable("SPRING_APPLICATION_JSON", "{}")
				.environmentVariable("SPRING_APPLICATION_INDEX", "${vcap.application.instance_index}")
				.environmentVariable("SPRING_CLOUD_APPLICATION_GUID", "${vcap.application.name}:${vcap.application.instance_index}")
				.instances(1)
				.memory(1024)
				.name("test-group-test-application-id")
				.service("test-service-2")
				.service("test-service-1")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			Collections.singletonMap(GROUP_PROPERTY_KEY, "test-group")));

		assertThat(deploymentId, equalTo("test-group-test-application-id"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployDockerResource() throws IOException {
		Resource resource = new DockerResource("somecorp/someimage:latest");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.dockerImage("somecorp/someimage:latest")
				.buildpack(deploymentProperties.getBuildpack())
				.disk(1024)
				.environmentVariables(defaultEnvironmentVariables())
				.instances(1)
				.memory(1024)
				.name("test-application-id")
				.service("test-service-2")
				.service("test-service-1")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			Collections.emptyMap()));

		assertThat(deploymentId, equalTo("test-application-id"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfCrashedApplicationIsFailed() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("CRASHED")
				.index("1")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.failed));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfDownApplicationIsDeploying() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("DOWN")
				.index("1")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.deploying));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfFlappingApplicationIsDeployed() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("FLAPPING")
				.index("1")
				.build())
			.build()));

		AppStatus status = deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.deployed));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfRunningApplicationIsDeployed() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("RUNNING")
				.index("1")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.deployed));
		assertThat(status.getInstances().get("test-application-0").toString(), equalTo("CloudFoundryAppInstanceStatus[test-application-0 : deployed]"));
		assertThat(status.getInstances().get("test-application-0").getAttributes(), equalTo(Collections.singletonMap("guid","test-application:0")));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfStartingApplicationIsDeploying() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("STARTING")
				.index("1")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.deploying));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfUnknownApplicationIsUnknown() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("UNKNOWN")
				.index("1")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.unknown));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusWithAbnormalInstanceStateThrowsException() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("ABNORMAL")
				.index("1")
				.build())
			.build()));

		try {
			this.deployer.status("test-application-id").getState();
			Assert.fail();
		} catch (IllegalStateException e) {
			assertThat(e.getMessage(), containsString("Unsupported CF state"));
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusWithFailingCAPICallRetries() throws Exception{
		AtomicInteger i = new AtomicInteger();
		Mono<ApplicationDetail> m = Mono.create(s -> {
			if (i.incrementAndGet() == 2) {
				s.success(ApplicationDetail.builder()
					.diskQuota(0)
					.id("test-application-id")
					.instances(1)
					.memoryLimit(0)
					.name("test-application")
					.requestedState("RUNNING")
					.runningInstances(1)
					.stack("test-stack")
					.instanceDetail(InstanceDetail.builder()
						.state("UNKNOWN")
						.index("1")
						.build())
					.build());
			}
			else {
				s.error(new RuntimeException("Simulated Server Side error"));
			}
		});
		givenRequestGetApplication("test-application-id", m);

		DeploymentState state = this.deployer.status("test-application-id").getState();
		assertThat(state, is(DeploymentState.unknown));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusWithFailingCAPICallRetriesEventualError() throws Exception{
		AtomicInteger i = new AtomicInteger();
		Mono<ApplicationDetail> m = Mono.create(s -> {
			if (i.incrementAndGet() == 12) { // 12 is more than the number of retries
				s.success(ApplicationDetail.builder()
					.diskQuota(0)
					.id("test-application-id")
					.instances(1)
					.memoryLimit(0)
					.name("test-application")
					.requestedState("RUNNING")
					.runningInstances(1)
					.stack("test-stack")
					.instanceDetail(InstanceDetail.builder()
						.state("UNKNOWN")
						.index("1")
						.build())
					.build());
			}
			else {
				s.error(new RuntimeException("Simulated Server Side error"));
			}
		});
		givenRequestGetApplication("test-application-id", m);
		this.deployer.deploymentProperties.setStatusTimeout(200); // Will cause wait of 20ms then 40ms,80ms

		DeploymentState state = this.deployer.status("test-application-id").getState();
		assertThat(state, is(DeploymentState.error));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusWithErrorThrownOnBlocking() throws Exception{
		AtomicInteger i = new AtomicInteger();
		Mono<ApplicationDetail> m = Mono.delay(Duration.ofSeconds(5)).then(
			Mono.create(s -> {
				i.incrementAndGet();
				s.success(ApplicationDetail.builder()
					.diskQuota(0)
					.id("test-application-id")
					.instances(1)
					.memoryLimit(0)
					.name("test-application")
					.requestedState("RUNNING")
					.runningInstances(1)
					.stack("test-stack")
					.instanceDetail(InstanceDetail.builder()
						.state("UNKNOWN")
						.index("1")
						.build())
					.build());
		}));
		givenRequestGetApplication("test-application-id", m);
		this.deployer.deploymentProperties.setApiTimeout(1);// Is less than the delay() above

		DeploymentState state = this.deployer.status("test-application-id").getState();
		assertThat(state, is(DeploymentState.error));
		assertThat(i.get(), is(0));
	}

	@Test
	public void undeploy() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("RUNNING")
				.index("1")
				.build())
			.build()));
		givenRequestDeleteApplication("test-application-id", Mono.empty());

		this.deployer.undeploy("test-application-id");

	}

	private void givenRequestDeleteApplication(String id, Mono<Void> response) {
		given(this.operations.applications()
			.delete(DeleteApplicationRequest.builder()
				.deleteRoutes(true)
				.name(id)
				.build()))
			.willReturn(response);
	}

	@SuppressWarnings("unchecked")
	private void givenRequestGetApplication(String id, Mono<ApplicationDetail> response, Mono<ApplicationDetail>... responses) {
		given(this.operations.applications()
			.get(GetApplicationRequest.builder()
				.name(id)
				.build()))
			.willReturn(response, responses);
	}

	private void givenRequestPushApplication(PushApplicationManifestRequest request, Mono<Void> response) {
		given(this.operations.applications()
			.pushManifest(request))
			.willReturn(response);
	}

	private Map<String,String> defaultEnvironmentVariables() {
		Map<String, String> environmentVariables = new HashMap<>();
		environmentVariables.put("SPRING_APPLICATION_JSON", "{}");
		addGuidAndIndex(environmentVariables);
		return environmentVariables;
	}

	private void addGuidAndIndex(Map<String, String> environmentVariables) {
		environmentVariables.put("SPRING_APPLICATION_INDEX", "${vcap.application.instance_index}");
		environmentVariables.put("SPRING_CLOUD_APPLICATION_GUID", "${vcap.application.name}:${vcap.application.instance_index}");
	}
}
