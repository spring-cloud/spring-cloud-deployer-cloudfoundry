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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.cloudfoundry.util.test.TestSubscriber;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Runs integration tests for {@link CloudFoundryTaskLauncher}, using the production configuration,
 * that may be configured via {@link CloudFoundryDeployerProperties}.
 *
 * Tests are only run if a successful connection can be made at startup.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = CloudFoundryDeployerProperties.class)
@IntegrationTest
@Ignore
public class CloudFoundryTaskLauncherIntegrationTests {

	private static final Logger log = LoggerFactory.getLogger(CloudFoundryTaskLauncherIntegrationTests.class);

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
		envProperties.put("organization", "spring-cloud");
		envProperties.put("space", "production");

		request = new AppDeploymentRequest(
			new AppDefinition("timestamp", Collections.emptyMap()),
			context.getResource("classpath:timestamp-task-1.0.0.BUILD-SNAPSHOT-exec-2.jar"),
			envProperties);

		taskLauncher = new CloudFoundryTaskLauncher(cfAvailable.getResource());
	}

	@Test
	public void testNonExistentAppsStatus() {
		assertThat(taskLauncher.status("foo").getState(), is(LaunchState.unknown));
	}


	@Test
	public void testSimpleLaunch() throws InterruptedException {

		TestSubscriber<String> subscriber = new TestSubscriber<>();

		taskLauncher.asyncLaunch(request).subscribe(subscriber
				.assertEquals("timestamp"));

//		Mono<Boolean> exists = taskLauncher.taskExists(request);
//
//		exists.subscribe(subscriber
//			.assertCount(1)
//			.assertEquals(false));

		subscriber.verify(Duration.ofDays(1));
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
