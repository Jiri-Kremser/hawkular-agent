/*
 * Copyright 2015-2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.javaagent.itest.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.management.ObjectName;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.RealmCallback;

import org.hawkular.agent.javaagent.JavaAgentEngine;
import org.hawkular.agent.javaagent.config.ConfigManager;
import org.hawkular.agent.javaagent.config.Configuration;
import org.hawkular.agent.monitor.service.ServiceStatus;
import org.hawkular.dmr.api.OperationBuilder;
import org.hawkular.dmrclient.Address;
import org.hawkular.dmrclient.JBossASClient;
import org.hawkular.inventory.api.model.DataEntity;
import org.hawkular.inventory.api.model.OperationType;
import org.hawkular.inventory.api.model.Resource;
import org.hawkular.inventory.api.model.ResourceType;
import org.hawkular.inventory.api.model.StructuredData;
import org.hawkular.inventory.json.InventoryJacksonConfig;
import org.hawkular.inventory.paths.CanonicalPath;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.testng.Assert;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public abstract class AbstractITest {

    private static final Logger log = Logger.getLogger(AbstractITest.class.getName());

    private static volatile boolean hawkularServerIsReady = false;

    protected static final int ATTEMPT_COUNT = 500;
    protected static final long ATTEMPT_DELAY = 5000;

    protected static final String hawkularHost;
    protected static final int hawkularHttpPort;
    protected static final int hawkularManagementPort;
    protected static final String hawkularAuthHeader;

    protected static final String authentication;
    protected static final String baseMetricsUri;
    protected static final String baseGwUri;
    protected static final String baseInvUri;
    protected static final String agentJolokiaUri; // the Jolokia endpoint that we can use to talk to the agent MBean

    protected static final String managementUser = System.getProperty("hawkular.agent.itest.mgmt.user");
    protected static final String managementPasword = System.getProperty("hawkular.agent.itest.mgmt.password");
    protected static final String hawkularTestUser = System.getProperty("hawkular.itest.rest.user");
    protected static final String hawkularTestPasword = System.getProperty("hawkular.itest.rest.password");

    private static final String tenantId = System.getProperty("hawkular.agent.itest.rest.tenantId"); // see getTenantId
    protected static final String hawkularFeedId = System.getProperty("hawkular.agent.itest.rest.feedId");

    protected static final File agentConfigFile = new File(
            System.getProperty("hawkular.agent.itest.javaagent.configfile"));

    protected static final Object waitForAccountsLock = new Object();
    protected static final Object waitForAgentLock = new Object();

    protected static final ObjectName AGENT_MBEAN_OBJECT_NAME;

    protected static final File plainWildFlyHome;

    static {
        String h = System.getProperty("hawkular.bind.address", "localhost");
        if ("0.0.0.0".equals(h)) {
            h = "localhost";
        }
        hawkularHost = h;

        int hawkularPortOffset = Integer.parseInt(System.getProperty("hawkular.port.offset", "0"));
        hawkularHttpPort = hawkularPortOffset + 8080;
        hawkularManagementPort = hawkularPortOffset + 9990;
        hawkularAuthHeader = Credentials.basic(hawkularTestUser, hawkularTestPasword);

        System.out.println("using REST user [" + hawkularTestUser + "] with password [" + hawkularTestPasword + "]");
        authentication = "{\"username\":\"" + hawkularTestUser + "\",\"password\":\"" + hawkularTestPasword + "\"}";
        baseMetricsUri = "http://" + hawkularHost + ":" + hawkularHttpPort + "/hawkular/metrics";
        baseGwUri = "ws://" + hawkularHost + ":" + hawkularHttpPort + "/hawkular/command-gateway";
        baseInvUri = "http://" + hawkularHost + ":" + hawkularHttpPort + "/hawkular/inventory";
        agentJolokiaUri = "http://" + hawkularHost + ":" + hawkularHttpPort + "/jolokia-war";

        try {
            AGENT_MBEAN_OBJECT_NAME = new ObjectName("org.hawkular:type=hawkular-javaagent");
        } catch (Exception e) {
            throw new RuntimeException("Cannot build agent mbean object name", e);
        }

        // some tests need to talk to the plain wildfly server (the one with the agent in it)
        String sysPropName = "hawkular.agent.itest.plain-wildfly.dir";
        String sysPropValue = System.getProperty(sysPropName);
        if (sysPropValue != null) {
            plainWildFlyHome = new File(sysPropValue);
            Assert.assertTrue(plainWildFlyHome.exists(),
                    sysPropName + " [" + plainWildFlyHome.getAbsolutePath() + "] does not exist");
        } else {
            plainWildFlyHome = null;
        }

    }

    public static String readNode(Class<?> caller, String nodeFileName) throws IOException {
        URL url = caller.getResource(caller.getSimpleName() + "." + nodeFileName);
        if (url != null) {
            StringBuilder result = new StringBuilder();
            try (Reader r = new InputStreamReader(url.openStream(), "utf-8")) {
                char[] buff = new char[1024];
                int len = 0;
                while ((len = r.read(buff, 0, buff.length)) != -1) {
                    result.append(buff, 0, len);
                }
            }
            return result.toString();
        } else {
            return null;
        }
    }

    public static void writeNode(Class<?> caller, ModelNode node, String nodeFileName)
            throws UnsupportedEncodingException, FileNotFoundException {
        URL callerUrl = caller.getResource(caller.getSimpleName() + ".class");
        if (!callerUrl.getProtocol().equals("file")) {
            throw new IllegalStateException(AbstractITest.class.getName()
                    + ".store() works only if the caller's class file is loaded using a file:// URL.");
        }
        String callerUrlPath = callerUrl.getPath();

        String nodePath = callerUrlPath.replaceAll("\\.class$", "." + nodeFileName);
        nodePath = nodePath.replace("/target/test-classes/", "/src/test/resources/");
        System.out.println("Storing a node to [" + nodePath + "]");

        File outputFile = new File(nodePath);
        if (!outputFile.getParentFile().exists()) {
            outputFile.getParentFile().mkdirs();
        }

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outputFile), "utf-8"))) {
            node.writeString(out, false);
        }
    }

    // Fields

    protected OkHttpClient client;
    protected ObjectMapper mapper;

    @BeforeMethod
    public void before() {
        JsonFactory f = new JsonFactory();
        this.mapper = new ObjectMapper(f);
        InventoryJacksonConfig.configure(mapper);
        this.client = new OkHttpClient();

    }

    @AfterMethod
    public void after() {
        // Trigger shutdown of the dispatcher's executor so this process can exit cleanly.
        this.client.dispatcher().executorService().shutdown();
    }

    protected String getNodeAttribute(ModelControllerClient mcc, ModelNode addressActual, String attributeName) {
        String actualAttributeValue = OperationBuilder
                .readAttribute()
                .address(addressActual)
                .name(attributeName)
                .includeDefaults()
                .execute(mcc)
                .assertSuccess()
                .getResultNode()
                .asString();
        return actualAttributeValue;
    }

    protected void writeNodeAttribute(ModelControllerClient mcc, ModelNode address, String attribute, String value) {
        OperationBuilder
                .writeAttribute()
                .address(address)
                .attribute(attribute, value)
                .execute(mcc)
                .assertSuccess();
    }

    protected void writeNodeAttribute(ModelControllerClient mcc, ModelNode address, String attribute, Integer value) {
        OperationBuilder
                .writeAttribute()
                .address(address)
                .attribute(attribute, value)
                .execute(mcc)
                .assertSuccess();
    }

    protected void assertNodeAttributeEquals(ModelControllerClient mcc, ModelNode address, String attribute,
            String expectedValue) {
        String actualAttributeValue = getNodeAttribute(mcc, address, attribute);
        Assert.assertEquals(actualAttributeValue, expectedValue);
    }

    protected void assertNodeEquals(ModelControllerClient mcc, ModelNode addressl, Class<?> caller,
            String expectedNodeFileName) {
        assertNodeEquals(mcc, addressl, caller, expectedNodeFileName, false);
    }

    protected void assertNodeEquals(ModelControllerClient mcc, ModelNode address, Class<?> caller,
            String expectedNodeFileName, boolean saveActual) {
        try {
            ModelNode actual = OperationBuilder
                    .readResource()
                    .address(address)
                    .includeRuntime()
                    .includeDefaults()
                    .recursive()
                    .execute(mcc)
                    .assertSuccess()
                    .getResultNode();
            String expected = readNode(caller, expectedNodeFileName);
            String actualString = actual.toString();
            if (saveActual) {
                writeNode(caller, actual, expectedNodeFileName + ".actual.txt");
            }
            Assert.assertEquals(actualString, expected);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void assertResourceCount(ModelControllerClient mcc, ModelNode address, String childType,
            int expectedCount) throws IOException {
        ModelNode request = new ModelNode();
        request.get(ModelDescriptionConstants.ADDRESS).set(address);
        request.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.READ_CHILDREN_RESOURCES_OPERATION);
        request.get(ModelDescriptionConstants.CHILD_TYPE).set(childType);
        request.get(ModelDescriptionConstants.INCLUDE_RUNTIME).set(true);
        ModelNode response = mcc.execute(request);
        if (response.hasDefined(ModelDescriptionConstants.OUTCOME)
                && response.get(ModelDescriptionConstants.OUTCOME)
                        .asString().equals(ModelDescriptionConstants.SUCCESS)) {
            ModelNode result = response.get(ModelDescriptionConstants.RESULT);
            List<Property> nodes = result.asPropertyList();
            AssertJUnit.assertEquals("Number of child nodes of [" + address + "] " + response, expectedCount,
                    nodes.size());
        } else if (expectedCount != 0) {
            AssertJUnit
                    .fail("Path [" + address + "] has no child nodes, expected [" + expectedCount + "]: " + response);
        }

    }

    protected void assertResourceExists(ModelControllerClient mcc, ModelNode address, boolean expectedExists)
            throws IOException {
        ModelNode request = new ModelNode();
        request.get(ModelDescriptionConstants.ADDRESS).set(address);
        request.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.READ_RESOURCE_OPERATION);
        request.get(ModelDescriptionConstants.INCLUDE_RUNTIME).set(true);
        ModelNode result = mcc.execute(request);

        String message = String.format("Model node [%s] %s unexpectedly", address.toString(),
                (expectedExists ? "does not exist" : "exists"));
        AssertJUnit.assertTrue(String.format(message, result),
                Operations.isSuccessfulOutcome(result) == expectedExists);

    }

    protected Resource getResource(String listPath, Predicate<Resource> predicate) throws Throwable {
        return getResource(listPath, predicate, ATTEMPT_COUNT, ATTEMPT_DELAY);
    }

    protected Resource getResource(String listPath, Predicate<Resource> predicate, int attemptCount, long attemptDelay)
            throws Throwable {
        String url = baseInvUri + listPath;
        Throwable e = null;
        for (int i = 0; i < attemptCount; i++) {
            try {
                String body = getWithRetries(url, attemptCount, attemptDelay);
                TypeFactory tf = mapper.getTypeFactory();
                JavaType listType = tf.constructCollectionType(ArrayList.class, Resource.class);
                JsonNode node = mapper.readTree(body);
                List<Resource> result = mapper.readValue(node.traverse(), listType);
                Optional<Resource> found = result.stream().filter(predicate).findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
                System.out.println("Could not find the right resource among [" + result.size() + "] resources on ["
                        + (i + 1) + "] of [" + attemptCount + "] attempts for URL [" + url + "]");
                // System.out.println(body);
            } catch (Throwable t) {
                // some initial attempts may fail so we continue
                e = t;
                System.out.println("URL [" + url + "] not ready yet on [" + (i + 1) + "] of [" + attemptCount
                        + "] attempts, about to retry after [" + attemptDelay + "]ms: " + t);
            }
            Thread.sleep(attemptDelay);
        }
        if (e != null) {
            throw e;
        } else {
            throw new AssertionError("Could not get [" + url + "]");
        }
    }

    protected List<Resource> getResources(String path, int minCount) throws Throwable {
        return getResources(path, minCount, ATTEMPT_COUNT, ATTEMPT_DELAY);
    }

    protected List<Resource> getResources(String path, int minCount, int attemptCount, long attemptDelay)
            throws Throwable {
        String url = baseInvUri + path;
        Throwable e = null;
        for (int i = 0; i < attemptCount; i++) {
            try {
                String body = getWithRetries(url, attemptCount, attemptDelay);
                TypeFactory tf = mapper.getTypeFactory();
                JavaType listType = tf.constructCollectionType(ArrayList.class, Resource.class);
                JsonNode node = mapper.readTree(body);
                List<Resource> result = mapper.readValue(node.traverse(), listType);
                if (result.size() >= minCount) {
                    return result;
                }
                System.out.println("Got only [" + result.size() + "] resources while expected [" + minCount + "] on ["
                        + (i + 1) + "] of [" + attemptCount + "] attempts for URL [" + url + "]");
                // System.out.println(body);
            } catch (Throwable t) {
                // some initial attempts may fail so we continue
                e = t;
                System.out.println("URL [" + url + "] not ready yet on [" + (i + 1) + "] of [" + attemptCount
                        + "] attempts, about to retry after [" + attemptDelay + "]ms: " + t);
            }
            Thread.sleep(attemptDelay);
        }
        if (e != null) {
            throw e;
        } else {
            throw new AssertionError("Could not get [" + url + "]");
        }
    }

    // expects entity not traversal path URL
    protected ResourceType getResourceType(String path, int attemptCount, long attemptDelay)
            throws Throwable {
        String url = baseInvUri + path;
        Throwable e = null;
        for (int i = 0; i < attemptCount; i++) {
            try {
                String body = getWithRetries(url, attemptCount, attemptDelay);
                TypeFactory tf = mapper.getTypeFactory();
                JavaType javaType = tf.constructType(ResourceType.class);
                JsonNode node = mapper.readTree(body);
                ResourceType result = mapper.readValue(node.traverse(), javaType);
                return result;
                // System.out.println(body);
            } catch (Throwable t) {
                // some initial attempts may fail so we continue
                e = t;
                System.out.println("URL [" + url + "] not ready yet on [" + (i + 1) + "] of [" + attemptCount
                        + "] attempts, about to retry after [" + attemptDelay + "]ms: " + t);
            }
            Thread.sleep(attemptDelay);
        }
        if (e != null) {
            throw e;
        } else {
            throw new AssertionError("Could not get [" + url + "]");
        }
    }

    // expects traversal, not entity path URL
    protected OperationType getOperationType(String path, int attemptCount, long attemptDelay)
            throws Throwable {
        String url = baseInvUri + path;
        Throwable e = null;
        int minCount = 1; // for now, we expect to retrieve only 1 type
        for (int i = 0; i < attemptCount; i++) {
            try {
                String body = getWithRetries(url, attemptCount, attemptDelay);
                TypeFactory tf = mapper.getTypeFactory();
                JavaType listType = tf.constructCollectionType(ArrayList.class, OperationType.class);
                JsonNode node = mapper.readTree(body);
                List<OperationType> result = mapper.readValue(node.traverse(), listType);
                if (result.size() >= minCount) {
                    return result.get(0); // we assume we are just getting 1
                }
                System.out.println("Got only " + result.size() + " operation types while expected " + minCount + " on "
                        + (i + 1) + " of " + attemptCount + " attempts for URL [" + url + "]");
                // System.out.println(body);
            } catch (Throwable t) {
                // some initial attempts may fail so we continue
                e = t;
                System.out.println("URL [" + url + "] not ready yet on [" + (i + 1) + "] of [" + attemptCount
                        + "] attempts, about to retry after [" + attemptDelay + "]ms: " + t);
            }
            Thread.sleep(attemptDelay);
        }
        if (e != null) {
            throw e;
        } else {
            throw new AssertionError("Could not get [" + url + "]");
        }
    }

    protected DataEntity getDataEntity(String path, int attemptCount, long attemptDelay)
            throws Throwable {
        String url = baseInvUri + path;
        Throwable e = null;
        for (int i = 0; i < attemptCount; i++) {
            try {
                String body = getWithRetries(url, attemptCount, attemptDelay);
                TypeFactory tf = mapper.getTypeFactory();
                JavaType javaType = tf.constructType(DataEntity.class);
                JsonNode node = mapper.readTree(body);
                DataEntity result = mapper.readValue(node.traverse(), javaType);
                return result;
                // System.out.println(body);
            } catch (Throwable t) {
                // some initial attempts may fail so we continue
                e = t;
                System.out.println("URL [" + url + "] not ready yet on [" + (i + 1) + "] of [" + attemptCount
                        + "] attempts, about to retry after [" + attemptDelay + "]ms: " + t);
            }
            Thread.sleep(attemptDelay);
        }
        if (e != null) {
            throw e;
        } else {
            throw new AssertionError("Could not get [" + url + "]");
        }
    }

    // path be entity not traversal and must have the "/d;configuration" at the end
    protected Map<String, StructuredData> getStructuredData(String path, int attemptCount, long attemptDelay)
            throws Throwable {
        DataEntity resConfigEntity = getDataEntity(path, attemptCount, attemptDelay);
        Map<String, StructuredData> resConfig = resConfigEntity.getValue().map();
        return resConfig;
    }

    protected String getWithRetries(String url) throws Throwable {
        return getWithRetries(url, ATTEMPT_COUNT, ATTEMPT_DELAY);
    }

    protected String getWithRetries(String url, int attemptCount, long attemptDelay) throws Throwable {
        Throwable e = null;
        for (int i = 0; i < attemptCount; i++) {
            try {
                Request request = newAuthRequest().url(url).build();
                Response response = client.newCall(request).execute();
                System.out.println("Received [" + response.code() + "][" + response.message() + "] from: " + url);
                AssertJUnit.assertEquals(200, response.code());
                return response.body().string();
            } catch (Throwable t) {
                // some initial attempts may fail so we continue
                e = t;
                System.out.println("URL [" + url + "] not ready yet on [" + (i + 1) + "] of [" + attemptCount
                        + "] attempts, about to retry after [" + attemptDelay + "]ms: " + t);
            }
            Thread.sleep(attemptDelay);
        }
        if (e != null) {
            throw e;
        } else {
            throw new AssertionError("Could not get [" + url + "]");
        }
    }

    protected void assertResourceNotInInventory(String listPath, Predicate<Resource> predicate, int attemptCount,
            long attemptDelay) throws Throwable {
        try {
            for (int i = 0; i < attemptCount; i++) {
                getResource(listPath, predicate, 1, 1);
                Thread.sleep(attemptDelay);
            }
        } catch (AssertionError expected) {
            return;
        }
        Assert.fail("resource is still in inventory. listPath=" + listPath);
    }

    protected Request.Builder newAuthRequest() {
        return new Request.Builder()
                .addHeader("Authorization", hawkularAuthHeader)
                .addHeader("Accept", "application/json")
                .addHeader("Hawkular-Tenant", getTenantId());
    }

    /**
     * Subclass tests can override this if they are putting things in other tenants.
     * @return the tenant to use when connecting to the hawkular server.
     */
    protected String getTenantId() {
        return tenantId;
    }

    /**
     * If a plain WildFly server was configured for the tests, this returns its data.
     * This will throw a runtime exception if there is no plain wildfly server in the test framework.
     *
     * @return configuration info about the test plain wildfly server
     */
    protected static WildFlyClientConfig getPlainWildFlyClientConfig() {
        return new WildFlyClientConfig();
    }

    /**
     * @return Client to the Hawkular WildFly Server.
     *
     * @see #getPlainWildFlyConfig()
     */
    protected static ModelControllerClient newPlainWildFlyModelControllerClient(WildFlyClientConfig config) {
        return newModelControllerClient(config.getHost(), config.getManagementPort());
    }

    /**
     * @return Client to the Hawkular WildFly Server.
     */
    protected static ModelControllerClient newHawkularModelControllerClient() {
        return newModelControllerClient(hawkularHost, hawkularManagementPort);
    }

    protected static ModelControllerClient newModelControllerClient(final String host, final int managementPort) {
        final CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                for (Callback current : callbacks) {
                    if (current instanceof NameCallback) {
                        NameCallback ncb = (NameCallback) current;
                        log.fine("ModelControllerClient is sending a username [" + managementUser + "]");
                        ncb.setName(managementUser);
                    } else if (current instanceof PasswordCallback) {
                        PasswordCallback pcb = (PasswordCallback) current;
                        log.fine("ModelControllerClient is sending a password [" + managementPasword + "]");
                        pcb.setPassword(managementPasword.toCharArray());
                    } else if (current instanceof RealmCallback) {
                        RealmCallback rcb = (RealmCallback) current;
                        log.fine("ModelControllerClient is sending a realm [" + rcb.getDefaultText() + "]");
                        rcb.setText(rcb.getDefaultText());
                    } else {
                        throw new UnsupportedCallbackException(current);
                    }
                }
            }
        };

        try {
            InetAddress inetAddr = InetAddress.getByName(host);
            log.fine("Connecting a ModelControllerClient to [" + host + ":" + managementPort + "]");
            return ModelControllerClient.Factory.create(inetAddr, managementPort, callbackHandler);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create management client", e);
        }
    }

    /**
     * Gets a list of all DMR children names.
     *
     * @param wfConfig tells you what WildFly Server to query for the data (if null talks to the Hawkular Server)
     * @param childTypeName the DMR name of the child type
     * @param parentAddress the parent whose children are to be returned
     * @return collection of child names
     */
    protected Collection<String> getDMRChildrenNames(
            WildFlyClientConfig wfConfig,
            String childTypeName,
            PathAddress parentAddress) {

        try (ModelControllerClient mcc = (wfConfig == null) ? newHawkularModelControllerClient()
                : newPlainWildFlyModelControllerClient(wfConfig)) {
            ModelNode result = OperationBuilder
                    .readChildrenNames()
                    .address(parentAddress)
                    .childType(childTypeName)
                    .execute(mcc)
                    .getResultNode();

            return result.asList().stream().map(n -> n.asString()).collect(Collectors.toList());

        } catch (IOException e) {
            throw new RuntimeException(
                    "Could not get [" + childTypeName + "] children of parent [" + parentAddress + "]", e);
        }
    }

    /**
     * Gets a list of all DMR children names.
     *
     * @param childTypeName the DMR name of the child type
     * @param parentAddress the parent whose children are to be returned
     * @return collection of child names
     */
    protected Collection<String> getDMRChildrenNames(String childTypeName, PathAddress parentAddress) {
        return getDMRChildrenNames(null, childTypeName, parentAddress);
    }

    protected void waitForHawkularServerToBeReady() throws Throwable {
        synchronized (waitForAccountsLock) {
            if (!hawkularServerIsReady) {
                Thread.sleep(10000);

                getWithRetries(baseInvUri + "/tenant");
                while (!getWithRetries(baseMetricsUri + "/status").contains("STARTED")) {
                    Thread.sleep(2000);
                }

                hawkularServerIsReady = true;
            }
        }
    }

    /**
     * Wait for the agent deployed in JMX to start.
     *
     * @param mcc
     * @return true if the agent is up, false if the wait timed out
     * @throws Throwable on error
     */
    protected boolean waitForAgentViaJMX() throws Throwable {
        synchronized (waitForAgentLock) {
            try {
                String status = "";
                int count = 0;
                while (!ServiceStatus.RUNNING.name().equals(status) && count++ < 12) {
                    Thread.sleep(5000);
                    String url = agentJolokiaUri + "/exec/" + AGENT_MBEAN_OBJECT_NAME.toString() + "/status";
                    String json = getWithRetries(url);
                    JsonNode results = mapper.readTree(json);
                    if (results.has("value")) {
                        status = results.get("value").asText("");
                    }
                }
                return ServiceStatus.RUNNING.name().equals(status);
            } catch (Exception e) {
                throw new RuntimeException("Could not get agent status", e);
            }
        }
    }

    /**
     * Wait for an agent deployed in WildFly as a WildFly subsystem to start.
     * @param mcc used to connect to WildFly
     * @return true if the agent is up, false if the wait timed out
     * @throws Throwable on error
     */
    protected boolean waitForAgent(ModelControllerClient mcc) throws Throwable {
        Address agentAddress = Address.parse("/subsystem=hawkular-wildfly-agent");
        return waitForAgent(mcc, agentAddress);
    }

    protected boolean waitForAgent(ModelControllerClient mcc, Address agentAddress) throws Throwable {
        synchronized (waitForAgentLock) {
            String agentPath = agentAddress.toAddressPathString();
            log.info("Checking [" + agentPath + "] status...");
            int count = 0;
            while (true && ++count <= 12) {
                Thread.sleep(5000);

                ModelNode op = JBossASClient.createRequest("status", agentAddress);
                ModelNode result = new JBossASClient(mcc).execute(op);
                if (JBossASClient.isSuccess(result)) {
                    String status = JBossASClient.getResults(result).asString().toUpperCase();
                    if (ServiceStatus.RUNNING.name().equals(status)) {
                        log.info("Agent [" + agentPath + "] status=" + status + ", continuing...");
                        return true;
                    } else {
                        log.info("Agent [" + agentPath + "] status=" + status + ", waiting...");
                    }
                }
            }
            return false;
        }
    }

    protected ModelNode getDMRAgentInventoryReport(String host, int managementPort) {
        try (ModelControllerClient mcc = newModelControllerClient(host, managementPort)) {
            Address agentAddress = Address.parse("/subsystem=hawkular-wildfly-agent");
            ModelNode op = JBossASClient.createRequest("inventoryReport", agentAddress);
            ModelNode inventoryReport = new JBossASClient(mcc).execute(op);
            if (JBossASClient.isSuccess(inventoryReport)) {
                return JBossASClient.getResults(inventoryReport);
            } else {
                throw new Exception(JBossASClient.getFailureDescription(inventoryReport));
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not get inventory report", e);
        }
    }

    protected JsonNode getJMXAgentInventoryReport() {
        try {
            String url = agentJolokiaUri + "/exec/" + AGENT_MBEAN_OBJECT_NAME.toString() + "/inventoryReport";
            String json = getWithRetries(url);
            JsonNode results = mapper.readTree(json);
            if (results.has("value")) {
                String inventoryJson = results.get("value").asText();
                return mapper.readTree(inventoryJson);
            }
            throw new Exception("No inventory report");
        } catch (Throwable e) {
            throw new RuntimeException("Could not get inventory report", e);
        }
    }

    protected void restartJMXAgent() {
        try {
            String url = agentJolokiaUri + "/exec/" + AGENT_MBEAN_OBJECT_NAME.toString() + "/";
            getWithRetries(url + "stop");
            getWithRetries(url + "start");
            if (!waitForAgentViaJMX()) {
                throw new Exception("Agent is not coming back up");
            }
        } catch (Throwable e) {
            throw new RuntimeException("Could not restart JMX agent", e);
        }
    }

    /**
     * Return the {@link CanonicalPath} of the only WildFly server present in inventory under the hawkular feed.
     * This is the Hawkular Server itself.
     *
     * @return path of hawkular wildfly server resource
     * @throws Throwable
     */
    protected CanonicalPath getHawkularWildFlyServerResourcePath() throws Throwable {
        List<Resource> servers = getResources("/traversal/f;" + hawkularFeedId + "/type=r", 2);
        List<Resource> wfs = servers.stream().filter(s -> "WildFly Server".equals(s.getType().getId()))
                .collect(Collectors.toList());
        AssertJUnit.assertEquals(1, wfs.size());
        return wfs.get(0).getPath();
    }

    /**
     * Return the {@link CanonicalPath} of the only WildFly Host Controller present in inventory under the feed
     * found in the given client config.
     *
     * @return path of host controller
     * @throws Throwable
     */
    protected CanonicalPath getHostController() throws Throwable {
        List<Resource> servers = getResources("/traversal/f;" + hawkularFeedId + "/type=r", 2);
        List<Resource> hcs = servers.stream().filter(s -> "Host Controller".equals(s.getType().getId()))
                .collect(Collectors.toList());
        AssertJUnit.assertEquals(1, hcs.size());
        return hcs.get(0).getPath();
    }

    protected File getTestApplicationFile() {
        String dir = System.getProperty("hawkular.agent.itest.staging.dir"); // the maven build put our test app here
        File app = new File(dir, "hawkular-javaagent-helloworld-war.war");
        Assert.assertTrue(app.isFile(), "Missing test application - build is bad: " + app.getAbsolutePath());
        return app;
    }

    protected Configuration getAgentConfigurationFromFile() throws Exception {
        Configuration config = new ConfigManager(agentConfigFile).getConfiguration(true);
        config.validate();
        return config;
    }

    // TODO: DO NOT USE THIS UNTIL WE FIX THE TEST CLASSPATH - THERE IS SOMETHING THAT IS CAUSING
    //       A NO-METHOD-FOUND EXCEPTION AND IT IS DUE TO SOME OLD STUFF IN TEST CLASSPATH.
    @Deprecated
    private JavaAgentEngine runAgent() throws Exception {
        log.info("Test is asking to start its own agent using config: " + agentConfigFile);
        JavaAgentEngine agent = new JavaAgentEngine(agentConfigFile);
        agent.startHawkularAgent();

        for (int attempt = 0; attempt < 60; attempt++, Thread.sleep(1000)) {
            if (agent.getStatus() == ServiceStatus.RUNNING) {
                return agent;
            }
        }

        throw new Exception("Agent failed to enter the RUNNING state");
    }
}
