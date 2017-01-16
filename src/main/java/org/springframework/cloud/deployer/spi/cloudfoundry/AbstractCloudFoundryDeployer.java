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

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.BUILDPACK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.cloudfoundry.AbstractCloudFoundryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Hooks;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.util.StringUtils;

/**
 * Base class dealing with configuration overrides on a per-deployment basis.
 *
 * @author Eric Bottard
 */
class AbstractCloudFoundryDeployer {

	final CloudFoundryDeploymentProperties deploymentProperties;

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryAppDeployer.class);

	/**
	 * How many ms to wait for API calls that ought to return "quickly". Not final so that tests can override.
	 */
	/*default*/ int shortApiCallsTimeoutMs = 10_000;


	AbstractCloudFoundryDeployer(CloudFoundryDeploymentProperties deploymentProperties) {
		this.deploymentProperties = deploymentProperties;
		Hooks.onOperator(op -> op.operatorStacktrace());
	}

	int memory(AppDeploymentRequest request) {
		String withUnit = request.getDeploymentProperties()
			.getOrDefault(AppDeployer.MEMORY_PROPERTY_KEY, this.deploymentProperties.getMemory());
		return (int) ByteSizeUtils.parseToMebibytes(withUnit);
	}

	Set<String> servicesToBind(AppDeploymentRequest request) {
		Set<String> services = new HashSet<>();
		services.addAll(this.deploymentProperties.getServices());
		services.addAll(StringUtils.commaDelimitedListToSet(request.getDeploymentProperties().get(SERVICES_PROPERTY_KEY)));
		return services;
	}

	int diskQuota(AppDeploymentRequest request) {
		String withUnit = request.getDeploymentProperties()
			.getOrDefault(AppDeployer.DISK_PROPERTY_KEY, this.deploymentProperties.getDisk());
		return (int) ByteSizeUtils.parseToMebibytes(withUnit);
	}

	String buildpack(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(BUILDPACK_PROPERTY_KEY))
			.orElse(this.deploymentProperties.getBuildpack());
	}

	Predicate<Throwable> isNotFoundError() {
		return t -> {
			logger.debug("isNotFoundError? Got Exception" + t);
			return t instanceof AbstractCloudFoundryException && ((AbstractCloudFoundryException) t).getStatusCode() == HttpStatus.NOT_FOUND.value();};
	}

}
