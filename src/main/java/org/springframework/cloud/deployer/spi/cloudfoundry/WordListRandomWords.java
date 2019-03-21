/*
 * Copyright 2013-2016 the original author or authors.
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generate random nouns and adjectives from a list.
 */
public class WordListRandomWords {

	private final List<String> adjectives;

	private final List<String> nouns;

	private final Random random;

	WordListRandomWords() {
		this(getWordList("adjectives.txt"), getWordList("nouns.txt"), new SecureRandom());
	}

	private WordListRandomWords(List<String> adjectives, List<String> nouns, Random random) {
		this.adjectives = adjectives;
		this.nouns = nouns;
		this.random = random;
	}

	public String getAdjective() {
		return this.adjectives.get(this.random.nextInt(this.adjectives.size()));
	}

	public String getNoun() {
		return this.nouns.get(this.random.nextInt(this.nouns.size()));
	}

	private static BufferedReader getFileReader(String filename) {
		InputStream inputStream = WordListRandomWords.class.getClassLoader().getResourceAsStream(filename);
		return new BufferedReader(new InputStreamReader(inputStream));
	}

	private static List<String> getWordList(String filename) {
		try (Stream<String> stream = getFileReader(filename).lines()) {
			return stream
					.map(String::trim)
					.collect(Collectors.toList());
		}
	}

}