package eu.wajja.web.fetcher;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import eu.wajja.web.fetcher.controller.ProxyController;
import eu.wajja.web.fetcher.controller.URLController;
import eu.wajja.web.fetcher.enums.Command;
import eu.wajja.web.fetcher.model.Result;

@DisallowConcurrentExecution
public class FetcherJob implements Job {

	private static final Logger LOGGER = LoggerFactory.getLogger(FetcherJob.class);

	private static final String METADATA_EPOCH = "epochSecond";
	private static final String METADATA_REFERENCE = "reference";
	private static final String METADATA_CONTENT = "content";
	private static final String METADATA_URL = "url";
	private static final String METADATA_CONTEXT = "context";
	private static final String METADATA_UUID = "uuid";
	private static final String METADATA_STATUS = "status";
	private static final String METADATA_CHILD = "childPages";
	private static final String METADATA_EXTERNAL = "externalPages";
	private static final String METADATA_COMMAND = "command";

	private static final String HTTP = "http://";
	private static final String HTTPS = "https://";
	private static final String ROBOTS = "robots.txt";

	private ObjectMapper objectMapper = new ObjectMapper();
	private URLController urlController;
	private ProxyController proxyController;
	private ThreadPoolExecutor executorService;

	private Long maxDepth;
	private Long maxPages;
	private Boolean waitJavascript;
	private List<String> excludedDataRegex;
	private List<String> excludedLinkRegex;
	private Long maxPagesCount = 0l;
	private String threadId;
	private String crawlerUserAgent;

	private List<String> tmpList = new ArrayList<>();
	protected Map<String, Set<String>> disallowedLocations = new HashMap<>();
	protected Map<String, Set<String>> allowedLocations = new HashMap<>();
	protected Map<String, Set<String>> sitemapLocations = new HashMap<>();

	@SuppressWarnings("unchecked")
	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {

		JobDataMap dataMap = context.getJobDetail().getJobDataMap();
		Consumer<Map<String, Object>> consumer = (Consumer<Map<String, Object>>) dataMap.get(WebFetcher.PROPERTY_CONSUMER);
		String initialUrl = dataMap.getString(WebFetcher.PROPERTY_URL);

		String dataFolder = dataMap.getString(WebFetcher.PROPERTY_DATAFOLDER);
		Boolean readRobot = dataMap.getBoolean(WebFetcher.PROPERTY_READ_ROBOT);
		Long threads = dataMap.getLong(WebFetcher.PROPERTY_THREADS);

		executorService = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads.intValue());

		this.maxDepth = dataMap.getLong(WebFetcher.PROPERTY_MAX_DEPTH);
		this.maxPages = dataMap.getLong(WebFetcher.PROPERTY_MAX_PAGES);
		this.threadId = dataMap.getString(WebFetcher.PROPERTY_THREAD_ID);
		this.waitJavascript = dataMap.getBoolean(WebFetcher.PROPERTY_JAVASCRIPT);
		this.excludedDataRegex = (List<String>) dataMap.get(WebFetcher.PROPERTY_EXCLUDE_DATA);
		this.excludedLinkRegex = (List<String>) dataMap.get(WebFetcher.PROPERTY_EXCLUDE_LINK);
		this.crawlerUserAgent = dataMap.getString(WebFetcher.PROPERTY_CRAWLER_USER_AGENT);

		if (proxyController == null) {

			proxyController = new ProxyController(dataMap.getString(WebFetcher.PROPERTY_PROXY_USER),
					dataMap.getString(WebFetcher.PROPERTY_PROXY_PASS),
					dataMap.getString(WebFetcher.PROPERTY_PROXY_HOST),
					dataMap.getLong(WebFetcher.PROPERTY_PROXY_PORT),
					dataMap.getBoolean(WebFetcher.PROPERTY_SSL_CHECK),
					dataMap.getString(WebFetcher.PROPERTY_CHROME_DRIVER),
					dataMap.getBoolean(WebFetcher.PROPERTY_JAVASCRIPT),
					dataMap.getLong(WebFetcher.PROPERTY_TIMEOUT));
		}

		if (urlController == null) {

			urlController = new URLController(
					proxyController.getProxy(),
					dataMap.getLong(WebFetcher.PROPERTY_TIMEOUT),
					dataMap.getString(WebFetcher.PROPERTY_CRAWLER_USER_AGENT),
					dataMap.getString(WebFetcher.PROPERTY_CRAWLER_REFERER));

		}

		LOGGER.info("Starting fetch for thread : {}, url : {}", threadId, initialUrl);

		String id = Base64.getEncoder().encodeToString(initialUrl.getBytes());

		// Read the robot.txt first

		if (readRobot) {

			Pattern p = Pattern.compile("(http).*(\\/\\/)[^\\/]{2,}(\\/)");
			Matcher m = p.matcher(initialUrl);

			if (m.find()) {
				String robotUrl = m.group(0) + ROBOTS;
				readRobot(initialUrl, robotUrl);

			} else {
				LOGGER.warn("Failed to find robot.txt url {}", initialUrl);
			}

		}

		// Extract the site content
		extractUrl(consumer, initialUrl, initialUrl, 0l);

		while (executorService.getActiveCount() > 0) {

			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				LOGGER.error("Failed to sleep in thread", e);
				Thread.currentThread().interrupt();
			}
		}

		// Compare to previously extracted content and delete delta
		deleteOldDocuments(consumer, dataFolder, id);

		// Close all proxy connections
		proxyController.close();

		LOGGER.info("Finished Thread {}", threadId);

	}

	private void readRobot(String initialUrl, String robotUrl) {

		Result result = urlController.getURL(robotUrl, initialUrl);

		if (result.getContent() != null) {

			try (Scanner scanner = new Scanner(IOUtils.toString(result.getContent(), StandardCharsets.UTF_8.name()))) {

				String userAgent = "*";

				while (scanner.hasNextLine()) {

					String line = scanner.nextLine().trim();

					if (!line.startsWith("#") && !line.isEmpty()) {

						if (line.startsWith("User-agent:")) {
							userAgent = line.replace("User-agent:", "").trim();

						} else if (line.startsWith("Disallow:")) {

							String perm = line.replace("Disallow:", "").trim();

							if (!disallowedLocations.containsKey(userAgent)) {
								disallowedLocations.put(userAgent, new HashSet<>());
							}

							disallowedLocations.get(userAgent).add(perm);

						} else if (line.startsWith("Allow:")) {

							String perm = line.replace("Allow:", "").trim();

							if (!allowedLocations.containsKey(userAgent)) {
								allowedLocations.put(userAgent, new HashSet<>());
							}

							allowedLocations.get(userAgent).add(perm);

						} else if (line.startsWith("Sitemap:")) {

							String perm = line.replace("Sitemap:", "").trim();

							if (!sitemapLocations.containsKey(userAgent)) {
								sitemapLocations.put(userAgent, new HashSet<>());
							}

							sitemapLocations.get(userAgent).add(perm);
						}
					}
				}

			} catch (Exception e) {
				LOGGER.error("Failed to parse robots.txt from url {}", robotUrl, e);
			}

		} else {
			LOGGER.warn("Failed to read robot.txt url, status {}, {}, {}", result.getCode(), initialUrl, result.getMessage());
		}
	}

	private void extractUrl(Consumer<Map<String, Object>> consumer, String urlStringTmp, String rootUrl, Long depth) {

		String urlString = null;

		try {

			if (maxPages != 0 && maxPagesCount >= maxPages) {
				return;
			}

			urlString = getUrlString(urlStringTmp, rootUrl);
			String uuid = UUID.randomUUID().toString();

			if (tmpList.contains(urlString)) {
				return;
			} else {

				tmpList.add(urlString);

				Set<String> disallowedList = new HashSet<>();

				if (disallowedLocations.containsKey("*")) {
					disallowedList = disallowedLocations.get("*");
				}

				if (disallowedLocations.containsKey(crawlerUserAgent)) {
					disallowedList.addAll(disallowedLocations.get(crawlerUserAgent));
				}

				if (!disallowedList.isEmpty()) {

					String regex = (".*(" + String.join(")|(", disallowedList).replace("*", ".*").replace("/", "\\/") + ").*").trim();
					Pattern p = Pattern.compile(regex);
					Matcher m = p.matcher(urlString);

					if (m.find()) {
						return;
					}
				}

			}

			Result result = urlController.getURL(urlString, rootUrl);

			if (result != null && result.getContent() != null) {

				List<String> childPages = extractContent(consumer, result, uuid, depth);
				Optional.ofNullable(childPages).orElse(new ArrayList<>()).stream().forEach(childUrl -> executorService.execute(() -> extractUrl(consumer, childUrl, result.getRootUrl(), depth + 1)));

			}

		} catch (Exception e) {
			LOGGER.error("Failed to retrieve URL from thread {}, url {}", threadId, urlString, e);
		}

	}

	@SuppressWarnings("unchecked")
	private List<String> extractContent(Consumer<Map<String, Object>> consumer, Result result, String uuid, Long depth) throws IOException {

		byte[] bytes = result.getContent();
		Map<String, List<String>> headers = result.getHeaders();

		Map<String, Object> metadata = new HashMap<>();
		metadata.put(METADATA_REFERENCE, Base64.getEncoder().encodeToString(result.getUrl().getBytes()));
		metadata.put(METADATA_CONTENT, Base64.getEncoder().encodeToString(bytes));
		metadata.put(METADATA_EPOCH, LocalDateTime.now().toEpochSecond(ZoneOffset.UTC));
		metadata.put(METADATA_URL, result.getUrl());
		metadata.put(METADATA_UUID, uuid);
		metadata.put(METADATA_STATUS, 200);
		metadata.put(METADATA_CONTEXT, result.getRootUrl());
		metadata.put(METADATA_COMMAND, Command.ADD.toString());

		headers.entrySet().stream().filter(entry -> entry.getKey() != null).forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));

		if (headers.containsKey("Content-Type") && headers.get("Content-Type").get(0).contains("html")) {

			String bodyHtml = null;

			if (waitJavascript) {

				proxyController.getDriver().get(result.getUrl());
				bodyHtml = proxyController.getDriver().getPageSource();

				metadata.put(METADATA_CONTENT, Base64.getEncoder().encodeToString(bodyHtml.getBytes()));

			} else {
				bodyHtml = IOUtils.toString(bytes, StandardCharsets.UTF_8.name());
			}

			org.jsoup.nodes.Document document = Jsoup.parse(bodyHtml);
			Elements elements = document.getElementsByAttribute("href");

			String simpleUrlString = getSimpleUrl(result.getRootUrl());

			List<String> childPages = elements.stream().map(e -> e.attr("href"))
					.filter(href -> (!href.startsWith(HTTP) && !href.startsWith(HTTPS) && !href.startsWith("mailto") && !href.startsWith("javascript") && !href.endsWith(".css") && !href.endsWith(".js")) || href.startsWith(HTTP + simpleUrlString) || href.startsWith(HTTPS + simpleUrlString))
					.filter(href -> !href.equals("/") && !href.startsWith("//"))
					.filter(href -> excludedLinkRegex.stream().noneMatch(ex -> href.matches(ex)))
					.sorted()
					.collect(Collectors.toList());

			List<String> externalPages = elements.stream().map(e -> e.attr("href")).filter(href -> (href.startsWith(HTTP) || href.startsWith(HTTPS)) && !href.startsWith(HTTP + simpleUrlString) && !href.startsWith(HTTPS + simpleUrlString)).collect(Collectors.toList());

			metadata.put(METADATA_CHILD, new ArrayList<>(new HashSet<>(childPages)));
			metadata.put(METADATA_EXTERNAL, new ArrayList<>(new HashSet<>(externalPages)));

		}

		if (excludedDataRegex.stream().noneMatch(ex -> result.getUrl().matches(ex))) {

			maxPagesCount++;
			consumer.accept(metadata);
			LOGGER.info("Thread {}, status {}, pages {}, depth {}, url {}, message {}, rootUrl {}, size {}, tmpList {}", threadId, result.getCode(), maxPagesCount, depth, result.getUrl(), result.getMessage(), result.getRootUrl(), metadata.get(METADATA_CONTENT).toString().length(), tmpList.size());

		} else {
			LOGGER.info("Excluded Thread {}, status {}, pages {}, depth {}, url {}, message {}, rootUrl {}, size {}, tmpList {}", threadId, result.getCode(), maxPagesCount, depth, result.getUrl(), result.getMessage(), result.getRootUrl(), result.getContent().length, tmpList.size());
		}

		List<String> childPages = (List<String>) metadata.get(METADATA_CHILD);
		Long newDepth = depth + 1;

		if (maxDepth == 0 || newDepth <= maxDepth) {
			return childPages;
		}

		return new ArrayList<>();

	}

	private String getUrlString(String urlString, String rootUrl) throws MalformedURLException {

		urlString = urlString.trim();

		if (!urlString.startsWith("http") && urlString.startsWith("/")) {

			URL urlRoot = new URL(rootUrl);
			String path = urlRoot.getPath();

			if (StringUtils.isEmpty(path) || path.equals("/")) {

				if (urlRoot.toString().endsWith("/") && urlString.startsWith("/")) {
					urlString = urlRoot + urlString.substring(1);
				} else {
					urlString = urlRoot + urlString;
				}

			} else {
				urlString = rootUrl.replace(path, "") + urlString;
			}

		} else if (!urlString.startsWith("http") && !urlString.startsWith("/")) {

			URL urlRoot = new URL(rootUrl);
			String path = urlRoot.getPath();

			if (StringUtils.isEmpty(path) || path.equals("/")) {

				urlString = urlRoot + "/" + urlString;
			} else {
				urlString = urlRoot.toString().substring(0, urlRoot.toString().lastIndexOf('/') + 1) + urlString;
			}
		}

		if (!urlString.startsWith("http") && !urlString.startsWith("/")) {
			urlString = rootUrl + urlString;
		}

		if (urlString.endsWith("/") && !urlString.equals(rootUrl)) {
			urlString = urlString.substring(0, urlString.lastIndexOf('/'));
		}

		return urlString;
	}

	private String getSimpleUrl(String urlString) {

		try {

			URL url = new URL(urlString);

			StringBuilder stringBuilder = new StringBuilder();
			stringBuilder.append(url.getHost());

			if (url.getPort() > 0) {
				stringBuilder.append(url.getPort());
			}

			String path = url.getPath();
			if (path.startsWith("/") && path.length() > 2) {
				path = path.substring(1, path.length());
			}

			if (path.split("/").length > 1) {
				stringBuilder.append("/").append(path.substring(0, path.indexOf('/')));
			}

			return stringBuilder.toString();

		} catch (MalformedURLException e) {
			LOGGER.error("MalformedURLException", e);
		}

		return urlString;
	}

	private void deleteOldDocuments(Consumer<Map<String, Object>> consumer, String dataFolder, String id) {

		if (dataFolder == null) {
			return;
		}

		Path indexPath = Paths.get(new StringBuilder(dataFolder).append("/fetched-data/").append(id).toString());

		if (!indexPath.toFile().exists()) {
			indexPath.toFile().mkdirs();
		}

		Path pathFile = Paths.get(new StringBuilder(dataFolder).append("/fetched-data/").append(id).append("_tmp.txt").toString());

		if (pathFile.toFile().exists()) {

			try {
				List<String> legacyFile = Arrays.asList(objectMapper.readValue(pathFile.toFile(), String[].class));
				List<String> urlsForDeletion = legacyFile.stream().filter(l -> !tmpList.contains(l)).collect(Collectors.toList());

				LOGGER.info("Thread deleting {}, deleting {} urls", threadId, urlsForDeletion.size());

				urlsForDeletion.parallelStream().forEach(url -> {

					String reference = Base64.getEncoder().encodeToString(url.getBytes());
					LOGGER.info("Thread Sending Deletion {} , reference {}", threadId, reference);

					Map<String, Object> metadata = new HashMap<>();
					metadata.put(METADATA_REFERENCE, reference);
					metadata.put(METADATA_COMMAND, Command.DELETE.toString());

					consumer.accept(metadata);

				});

				Files.delete(pathFile);

			} catch (IOException e) {
				LOGGER.warn("Failed to read legacy file", e);
			}
		}

		try {
			String json = objectMapper.writeValueAsString(tmpList);
			Files.write(pathFile, json.getBytes());
		} catch (IOException e) {
			LOGGER.warn("Failed to write tmp file to disk {}", pathFile, e);
		}

	}
}