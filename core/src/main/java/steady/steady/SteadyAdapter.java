package steady.steady;


import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.ConfigFileApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.rules.SpringClassRule;
import org.springframework.test.context.junit4.rules.SpringMethodRule;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

import static java.lang.Boolean.FALSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;


@SuppressWarnings("unchecked")
@Slf4j
@RunWith(Parameterized.class)
// Using the ConfigFileApplicationContextInitializer to load the application.proprerties or application.yml
// as per https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-testing.html#boot-features-configfileapplicationcontextinitializer-test-utility
@ContextConfiguration(initializers = ConfigFileApplicationContextInitializer.class)
@SpringBootTest()
public class SteadyAdapter {

    // -- STATIC METHODS -- //

    protected static String DEFAULT_TEST_SPECS_YML = "test-specs.yml";

    /**
     * This enables spring injection at class level
     */
    @ClassRule
    public static final SpringClassRule SCR = new SpringClassRule();

    protected static Map<String, Object> testSpecs = Collections.emptyMap();
    protected static Map<String, Object> defaults = Collections.emptyMap();


    /**
     * Return the location where the yaml with the test specification is located
     * the file will be loaded using the classloader.
     *
     * @return the path of the yaml to be loaded
     */
    protected static String getSecurityTestsYml() {
        return DEFAULT_TEST_SPECS_YML;
    }

    /**
     * Loads the tests from {@link #getSecurityTestsYml()}
     */
    protected static void loadTestSpecs() {
        Yaml yaml = new Yaml();

        InputStream inputStream = SteadyAdapter.class
                .getClassLoader()
                .getResourceAsStream(getSecurityTestsYml());
        if (inputStream == null) {
            throw new IllegalStateException("Could not load test spec from location: " + getSecurityTestsYml());
        }

        testSpecs = yaml.load(inputStream);
        defaults = loadDefaults();
    }


    /**
     * Load the default values for all tests, so anything set here will be used in all tests unless overwritten
     *
     * @return a Map with the defaults
     */
    protected static Map<String, Object> loadDefaults() {
        return Optional.ofNullable((Map<String, Object>) testSpecs.get("defaults")).orElse(Collections.emptyMap());
    }

    /**
     * Loads all params from the test specs to execute the tests
     *
     * @see #loadTestSpecs()
     * @see org.junit.runners.Parameterized
     */
    @Parameterized.Parameters(name = "{1} : {0}")
    public static Iterable<Object[]> loadTestParams() {
        loadTestSpecs();
        final List<Map<String, Object>> tests = (List<Map<String, Object>>) testSpecs.get("tests");

        if (tests == null || tests.isEmpty()) {
            log.warn("No tests will be executed, consider adding a file [{}] with the test specs", getSecurityTestsYml());
            return Collections.emptySet();
        }

        return tests.stream().flatMap(SteadyAdapter::buildTestParamsForPath).collect(Collectors.toList());


    }

    /**
     * Build the test params for each test inside a test path
     *
     * @param testPath the map containing all tests for a given test path
     * @return a stream with the Object array that represent the params
     */
    private static Stream<? extends Object[]> buildTestParamsForPath(Map<String, Object> testPath) {
        final String path = testPath.keySet()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No path was given for the given test " + testPath));
        final List<Map<String, Object>> namedTests = (List<Map<String, Object>>) testPath.get(path);

        return namedTests.stream().map(test -> new Object[]{path, getTestName(test), test});
    }

    // -- END OF STATIC METHODS -- //


    /**
     * This rule allows Spring injection at method level
     */
    @Rule
    public final SpringMethodRule springMethodRule = new SpringMethodRule();

    @Autowired
    protected WebApplicationContext context;


    @Parameterized.Parameter(0)
    public String path;
    @Parameterized.Parameter(1)
    public String name;
    @Parameterized.Parameter(2)
    public Map<String, Object> testSpec;

    private MockMvc mvc;


    @Before
    public void setUp() {
        mvc = setupMockMvc();
    }


    /**
     * Setup the mockmvc with security enabled
     */
    protected MockMvc setupMockMvc() {
        return MockMvcBuilders
                .webAppContextSetup(context)
                .build();
    }


    /**
     * Executes a single test with the given parameters
     */
    @Test
    public void performRequest() {

        Map<String, Object> test = new HashMap<>(defaults);
        test.putAll(testSpec);

        log.info("Executing test: {} - {}", path, name);
        final int expectedStatus = getStatusCode(test);

        try {
            final HttpMethod method = getMethod(test);
            final List<Object> urlVariables = getUrlVariables(test);
            final MediaType contentType = getContentType(test);
            final MediaType accept = getAccept(test);

            final MockHttpServletRequestBuilder request = buildMockMvcRequest(path, method, urlVariables, contentType, accept);

            // TODO process plugins
//            addAuthorization(test, request);

            addCustomHeaders(test, request);

            addRequestBody(test, request);

            prePerform(path, name, test, request);

            final ResultActions actions = mvc.perform(request);
            if (shouldPrint(test)) {
                actions.andDo(print());
            }

            final MvcResult mvcResult = actions.andReturn();

            postPerform(path, name, test, request, mvcResult);


            testStatusCode(expectedStatus, mvcResult);

            getResponseBody(test).ifPresent(responseBody -> testResponseBody(mvcResult, responseBody));

            getResponseBodyContains(test).ifPresent(responseBodyContains -> testResponseBody(mvcResult, responseBodyContains, true));


        } catch (Exception e) {
            log.warn("Error executing test {} for path {}", name, path, e);
            onError(path, name, test, e);
        }

        onFinish(path, name, test);

    }


    /**
     * Add custom headers from the spec to the request
     *
     * @param test    the test spec
     * @param request the MockMvcRequest
     * @see MockHttpServletRequestBuilder#header(String, Object...)
     */
    protected void addCustomHeaders(Map<String, Object> test, MockHttpServletRequestBuilder request) {
        Optional.ofNullable(test.get("headers"))
                .map(o -> ((Map<String, Object>) o))
                .filter(headers -> !headers.isEmpty())
                .ifPresent(headers -> headers.forEach(request::header));
    }

    /**
     * Build the {@link MockHttpServletRequestBuilder} with the given parameters
     *
     * @param path         the path of the request
     * @param method       the request method
     * @param urlVariables the url variables to be replaced
     * @param contentType  the content type
     * @param accept       the accept
     * @return a MockHttpServletRequestBuilder with all the info
     * @see MockMvcRequestBuilders#request(HttpMethod, String, Object...)
     */
    protected MockHttpServletRequestBuilder buildMockMvcRequest(String path, HttpMethod method, List<Object> urlVariables, MediaType contentType, MediaType accept) {
        return MockMvcRequestBuilders
                .request(method, path, urlVariables.toArray())
                .contentType(contentType)
                .accept(accept);
    }


    /**
     * Adds the request body from the test spec to the request
     *
     * @param test    the test spec
     * @param request the request
     */
    protected void addRequestBody(Map<String, Object> test, MockHttpServletRequestBuilder request) {
        Optional.ofNullable(test.get("requestBody"))
                .map(Object::toString)
                .map(String::trim)
                .ifPresent(request::content);
    }

    /**
     * Executes the test for the status code
     *
     * @param expectedStatus the expected status code
     * @param mvcResult      the result from the request
     */
    protected void testStatusCode(int expectedStatus, MvcResult mvcResult) {
        final int resultStatus = mvcResult.getResponse().getStatus();
        assertThat(resultStatus).isEqualTo(expectedStatus);
    }

    /**
     * Executes the test for the response body
     *
     * @param mvcResult    the result from the request
     * @param expectedBody the expected response body
     */
    protected void testResponseBody(MvcResult mvcResult, String expectedBody) {
        testResponseBody(mvcResult, expectedBody, false);
    }

    /**
     * Executes the test for the response body
     *
     * @param mvcResult    the result from the request
     * @param expectedBody the expected response body
     * @param contains     if true the expectedBody should be inside the response otherwise must be exact
     */
    protected void testResponseBody(MvcResult mvcResult, String expectedBody, boolean contains) {
        final String returnedBody;
        try {
            returnedBody = mvcResult.getResponse().getContentAsString();

            if (!contains) {
                final boolean responseBodyMatch = expectedBody.equals(returnedBody);
                if (!responseBodyMatch) {
                    log.error("Response body does not match");
                    log.error("Expected: {}", expectedBody);
                    log.error("Returned: {}", returnedBody);
                }
                assertThat(returnedBody).isEqualTo(expectedBody);
            } else {
                final boolean responseBodyMatch = returnedBody.contains(expectedBody);
                if (!responseBodyMatch) {
                    log.error("Response body does not match");
                    log.error("Expected to contain: {}", expectedBody);
                    log.error("Returned: {}", returnedBody);
                }
                assertThat(returnedBody).contains(expectedBody);
            }


        } catch (UnsupportedEncodingException e) {
            log.error("Could not read expectedBody", e);
            fail("Could not read expectedBody", e);
        }
    }

    /**
     * Get the expected response body from the test
     *
     * @param test the map containing the test spec
     * @return An optional string with the response body
     */
    protected Optional<String> getResponseBody(Map<String, Object> test) {
        return Optional.ofNullable(test.get("responseBody")).map(Object::toString).map(String::trim);
    }


    /**
     * Get a string that is expected to be in the response body
     *
     * @param test the map containing the test spec
     * @return an Optional with the string that must be matched
     */
    private Optional<String> getResponseBodyContains(Map<String, Object> test) {
        return Optional.ofNullable(test.get("responseBodyContains")).map(Object::toString).map(String::trim);
    }

    /**
     * Get the accepted {@link MediaType} for the request
     * <p> default to {@link MediaType#APPLICATION_JSON}
     *
     * @param test the test spec
     * @return the media type that should be passed in the Accept header
     * @see MediaType
     */
    protected MediaType getAccept(Map<String, Object> test) {
        return Optional.ofNullable(test.get("accept")).map(Object::toString).map(MediaType::parseMediaType).orElse(MediaType.APPLICATION_JSON);
    }

    /**
     * Get the content type {@link MediaType} for the request
     * <p> default to {@link MediaType#APPLICATION_JSON}
     *
     * @param test the test spec
     * @return the media type that should be passed in the Content-Type header
     * @see MediaType
     */
    protected MediaType getContentType(Map<String, Object> test) {
        return Optional.ofNullable(test.get("content-type")).map(Object::toString).map(MediaType::parseMediaType).orElse(MediaType.APPLICATION_JSON);
    }

    /**
     * The url Variables that should used to expand the test path
     *
     * @param test the test spec
     * @return a list containing all variables that should be used for url expansion
     * @see UriComponentsBuilder#fromUriString(String)
     */
    protected List<Object> getUrlVariables(Map<String, Object> test) {
        return Optional.ofNullable(test.get("urlVariables")).map(o -> ((List<Object>) o)).orElse(Collections.emptyList());
    }

    /**
     * Get the http method from the test
     *
     * @param test the test spec
     * @return the {@link HttpMethod} that should be used
     * <p>default to {@link HttpMethod#GET}</p>
     */
    protected HttpMethod getMethod(Map<String, Object> test) {
        return Optional.ofNullable(test.get("method")).map(Object::toString).map(HttpMethod::resolve).orElse(HttpMethod.GET);
    }

    /**
     * Get the expected status code from the test spec
     *
     * @param test the test spec
     * @return an Integer default: <b>200</b>
     */
    protected Integer getStatusCode(Map<String, Object> test) {
        return Optional.ofNullable(test.get("statusCode")).map(Object::toString).map(Integer::valueOf).orElse(200);
    }

    /**
     * Get the test name from the test spec
     *
     * @param test the test spec
     * @return the test name
     */
    protected static String getTestName(Map<String, Object> test) {
        return Optional.ofNullable(test.get("name")).map(Object::toString).orElse("UNNAMED");
    }

    /**
     * If the test should be printed to the console using {@link MockMvcResultHandlers#print()}
     *
     * @param test the test spec
     * @return true if should be printed false otherwise
     */
    protected boolean shouldPrint(Map<String, Object> test) {
        return Optional.ofNullable(test.get("print")).map(Object::toString).map(Boolean::parseBoolean).orElse(FALSE);
    }

    /**
     * Extension point that is called after finish each test
     *
     * @param path the path of the request
     * @param name the test name
     * @param test the test spec
     */
    protected void onFinish(String path, String name, Map<String, Object> test) {
        log.debug(":: On finish :: -> {} :: {}", path, name);
    }

    /**
     * Extension point that is called each time a test throws an exception
     *
     * @param path  the path of the request
     * @param name  the test name
     * @param test  the test spec
     * @param error the exception thrown by the test
     */
    protected void onError(String path, String name, Map<String, Object> test, Exception error) {
        log.debug(":: On error :: -> {} :: {}", path, name);
        throw new RuntimeException(error);
    }

    /**
     * Extension point that is called <b>after</b> each test execution
     *
     * @param path      the path of the request
     * @param name      the test name
     * @param test      the test spec
     * @param request   the MockMvcRequest
     * @param mvcResult the result from the request
     */
    protected void postPerform(String path, String name, Map<String, Object> test, MockHttpServletRequestBuilder request, MvcResult mvcResult) {
        log.debug(":: Post perform :: -> {} :: {}", path, name);
    }

    /**
     * Extension point that is called <b>before</b> each test execution
     *
     * @param path    the path of the request
     * @param name    the test name
     * @param test    the test spec
     * @param request the MockMvcRequest
     */
    protected void prePerform(String path, String name, Map<String, Object> test, MockHttpServletRequestBuilder request) {
        log.debug(":: Pre perform :: -> {} :: {}", path, name);
    }
}
