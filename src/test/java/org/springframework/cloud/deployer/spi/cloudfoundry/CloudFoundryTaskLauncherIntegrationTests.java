/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.cloud.deployer.spi.cloudfoundry;

import com.github.zafarkhaja.semver.Version;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.test.AbstractTaskLauncherIntegrationTests;
import org.springframework.cloud.deployer.spi.test.Timeout;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs integration tests for {@link CloudFoundry2630AndLaterTaskLauncher}, using the production configuration,
 * that may be configured via {@link CloudFoundryConnectionProperties}.
 *
 * Tests are only run if a successful connection can be made at startup.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 * @author Michael Minella
 * @author Ben Hale
 */
@ContextConfiguration(classes=CloudFoundryTaskLauncherIntegrationTests.Config.class)
public class CloudFoundryTaskLauncherIntegrationTests extends AbstractTaskLauncherIntegrationTests {

	@ClassRule
	public static CloudFoundryTestSupport cfAvailable = new CloudFoundryTestSupport();

	@Autowired
	private TaskLauncher taskLauncher;

	@Autowired
	private Version cloudControllerAPIVersion;

	/**
	 * Execution environments may override this default value to have tests wait longer for a deployment, for example if
	 * running in an environment that is known to be slow.
	 */
	protected double timeoutMultiplier = 1.0D;

	protected int maxRetries = 60;

	@Before
	public void init() {
		Assume.assumeTrue("Skipping TaskLauncher ITs on PCF<1.9 (2.65.0). Actual API version is " + cloudControllerAPIVersion,
			cloudControllerAPIVersion.greaterThanOrEqualTo(Version.forIntegers(2, 65, 0)));

		String multiplier = System.getenv("CF_DEPLOYER_TIMEOUT_MULTIPLIER");
		if (multiplier != null) {
			timeoutMultiplier = Double.parseDouble(multiplier);
		}
	}

	@Override
	protected TaskLauncher provideTaskLauncher() {
		return taskLauncher;
	}

	/*
	 * Allow for a small pause so that each each TL.destroy() at the end of tests actually completes,
	 * as this is asynchronous.
	 */
	@After
	public void pause() throws InterruptedException {
		Thread.sleep(500);
	}

	@Test
	@Override
	@Ignore("CF Deployer incorrectly reports status as failed instead of canceled")
	public void testSimpleCancel() throws InterruptedException {
		super.testSimpleCancel();
	}

	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(maxRetries, (int) (5000 * timeoutMultiplier));
	}

	@Override
	protected Timeout undeploymentTimeout() {
		return new Timeout(maxRetries, (int) (5000 * timeoutMultiplier));
	}



	/**
	 * This triggers the use of {@link CloudFoundryDeployerAutoConfiguration}.
	 *
	 * @author Eric Bottard
	 */
	@Configuration
	@EnableAutoConfiguration
	@EnableConfigurationProperties
	public static class Config {

	}

}
