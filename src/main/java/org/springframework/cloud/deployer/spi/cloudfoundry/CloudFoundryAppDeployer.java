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

import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.SetEnvironmentVariableApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Map;

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.stream.Stream.concat;
import static org.springframework.util.StringUtils.commaDelimitedListToSet;

/**
 * A deployer that targets Cloud Foundry using the public API.
 * 
 * @author Eric Bottard
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployer implements AppDeployer {


	public static final String MEMORY_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.memory";

	public static final String DISK_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.disk";

	public static final String SERVICES_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.services";

	private final CloudFoundryDeployerProperties properties;

	private final CloudFoundryOperations operations;

	public CloudFoundryAppDeployer(CloudFoundryDeployerProperties properties, CloudFoundryOperations operations) {
		this.properties = properties;
		this.operations = operations;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		DeploymentState state = status(request.getDefinition().getName()).getState();
		if (state != DeploymentState.unknown) {
			throw new IllegalStateException(String.format("App %s is already deployed with state %s",
				request.getDefinition().getName(), state));
		}

		asyncDeploy(request)
			.subscribe();

		return request.getDefinition().getName();
	}

	Mono<Void> asyncDeploy(AppDeploymentRequest request) {
		try {
			return operations.applications()
                .push(PushApplicationRequest.builder()
                    .name(request.getDefinition().getName())
                    .application(request.getResource().getInputStream())
                    .domain(properties.getDomain())
                    .buildpack(properties.getBuildpack())
                    .diskQuota(diskQuota(request))
                    .instances(instances(request))
                    .memory(memory(request))
                    .noStart(true)
                    .build())
                .after(() -> environmenVariables(request)
                    .flatMap(entry -> operations.applications()
                        .setEnvironmentVariable(SetEnvironmentVariableApplicationRequest.builder()
                            .name(request.getDefinition().getName())
                            .variableName(entry.getKey())
                            .variableValue(entry.getValue())
                            .build()))
                    .after() /* this after() merges all the envVariable Mono<Void>'s into 1 */)
                .after(() -> servicesToBind(request)
                    .flatMap(service -> operations.services()
                        .bind(BindServiceInstanceRequest.builder()
                            .applicationName(request.getDefinition().getName())
                            .serviceInstanceName(service)
                            .build()))
                    .after() /* this after() merges all the bindServices Mono<Void>'s into 1 */)
                .after(() -> operations.applications()
                    .start(StartApplicationRequest.builder()
                        .name(request.getDefinition().getName())
                        .build()));
		} catch (IOException e) {
			return Mono.error(e);
		}
	}

	@Override
	public void undeploy(String id) {
		asyncUndeploy(id).subscribe();
	}

	Mono<Void> asyncUndeploy(String id) {
		return operations.applications()
			.delete(
				DeleteApplicationRequest.builder()
					.deleteRoutes(true)
					.name(id)
					.build()
		);
	}

	@Override
	public AppStatus status(String id) {
		return asyncStatus(id)
			.get();
	}

	Mono<AppStatus> asyncStatus(String id) {
		return operations.applications()
			.get(GetApplicationRequest.builder()
				.name(id)
				.build())
			.then(ad -> createAppStatusBuilder(id, ad))
			.otherwise(e -> emptyAppStatusBuilder(id))
			.map(AppStatus.Builder::build);
	}


	private Flux<Map.Entry<String, String>> environmenVariables(AppDeploymentRequest request) {
		return Flux.fromStream(request.getDefinition().getProperties().entrySet().stream());
	}

	/**
	 * TODO: Should we join properties with override, or replace?
	 *
	 * @param request
	 * @return
     */
	private Flux<String> servicesToBind(AppDeploymentRequest request) {
		return Flux.fromStream(
			concat(
				properties.getServices().stream(),
				commaDelimitedListToSet(request.getEnvironmentProperties().get(SERVICES_PROPERTY_KEY)).stream()));
	}

	private int memory(AppDeploymentRequest request) {
		return parseInt(
			request.getEnvironmentProperties().getOrDefault(MEMORY_PROPERTY_KEY, valueOf(properties.getMemory())));
	}

	private int instances(AppDeploymentRequest request) {
		return parseInt(
			request.getEnvironmentProperties().getOrDefault(AppDeployer.COUNT_PROPERTY_KEY, "1"));
	}

	private int diskQuota(AppDeploymentRequest request) {
		return parseInt(
			request.getEnvironmentProperties().getOrDefault(DISK_PROPERTY_KEY, valueOf(properties.getDisk())));
	}

	private Mono<AppStatus.Builder> createAppStatusBuilder(String id, ApplicationDetail ad) {
		return emptyAppStatusBuilder(id)
			.then(b -> addInstances(b, ad));
	}

	private Mono<AppStatus.Builder> emptyAppStatusBuilder(String id) {
		return Mono.just(AppStatus.of(id));
	}

	private Mono<AppStatus.Builder> addInstances(AppStatus.Builder initial, ApplicationDetail ad) {
		return Flux.fromIterable(ad.getInstanceDetails())
				.zipWith(Flux.range(0, ad.getRunningInstances()))
				.reduce(initial, (b, inst) -> b.with(new CloudFoundryAppInstanceStatus(ad, inst.t1, inst.t2)));
	}

}
