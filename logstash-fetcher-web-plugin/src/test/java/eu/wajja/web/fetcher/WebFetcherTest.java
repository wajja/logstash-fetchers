package eu.wajja.web.fetcher;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.Test;
import org.logstash.plugins.ConfigurationImpl;

import co.elastic.logstash.api.Configuration;

public class WebFetcherTest {

	@Test
	public void testWebFetcher() throws IOException {

		Map<String, Object> configValues = new HashMap<>();
		configValues.put(WebFetcher.CONFIG_URLS.name(), Arrays.asList("http://localhost"));
		configValues.put(WebFetcher.CONFIG_DATA_FOLDER.name(), Files.createTempDirectory("tmp").toFile().getAbsolutePath());
		configValues.put(WebFetcher.CONFIG_EXCLUDE.name(), Arrays.asList("css"));
		configValues.put(WebFetcher.CONFIG_THREAD_POOL_SIZE.name(), 1l);
		configValues.put(WebFetcher.CONFIG_REFRESH_INTERVAL.name(), 1l);

		Configuration config = new ConfigurationImpl(configValues);
		WebFetcher webFetcher = new WebFetcher("test-id", config, null);
		webFetcher.stop();
		
		TestConsumer testConsumer = new TestConsumer();
		webFetcher.start(testConsumer);
		
		List<Map<String, Object>> events = testConsumer.getEvents();

		Assert.assertEquals(0, events.size());

	}

	private static class TestConsumer implements Consumer<Map<String, Object>> {

		private List<Map<String, Object>> events = new ArrayList<>();

		@Override
		public void accept(Map<String, Object> event) {
			synchronized (this) {
				events.add(event);
			}
		}

		public List<Map<String, Object>> getEvents() {
			return events;
		}
	}
}
