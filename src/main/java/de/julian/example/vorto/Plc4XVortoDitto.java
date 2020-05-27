package de.julian.example.vorto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.cli.*;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.plc4x.java.PlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.eclipse.ditto.client.DittoClient;
import org.eclipse.ditto.client.DittoClients;
import org.eclipse.ditto.client.configuration.BasicAuthenticationConfiguration;
import org.eclipse.ditto.client.configuration.MessagingConfiguration;
import org.eclipse.ditto.client.configuration.WebSocketMessagingConfiguration;
import org.eclipse.ditto.client.messaging.AuthenticationProviders;
import org.eclipse.ditto.client.messaging.MessagingProvider;
import org.eclipse.ditto.client.messaging.MessagingProviders;
import org.eclipse.ditto.client.twin.TwinFeatureHandle;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;
import org.eclipse.ditto.model.things.ThingId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * @author julian
 * Created by julian on 18.05.20
 */
public class Plc4XVortoDitto {

    private static final Logger logger = LoggerFactory.getLogger(Plc4XVortoDitto.class);

    private static final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

    public static final String FIELD_NAME = "value";
    private static String namespace;
    private static String modelName;
    private static String modelVersion;
    private static String modelMapping;
    private static String dittoEndpoint;
    private static String twinId;

    public static void main(String[] args) throws IOException {
        // create the command line parser
        parseCommandLineParameter(args);

        // Phase 1 - Fetch Data from Vorto
        HttpClient client = new HttpClient();
        String dittoJsonString = doHttpGet(client, String.format("https://vorto.eclipse.org/api/v1/generators/eclipseditto/models/%s.%s:%s?target=thingJson", namespace, modelName, modelVersion));
        String mappingJsonString = doHttpGet(client, String.format("https://vorto.eclipse.org/api/v1/models/%s.%s:%s/content/%s", namespace, modelName, modelVersion, modelMapping));

        // Map to JSON
        ObjectMapper om = new ObjectMapper();
        JsonNode dittoJson = om.reader().readTree(dittoJsonString);
        JsonNode mappingJson = om.reader().readTree(mappingJsonString);

        // Create Device in Ditto if not exists
        final MessagingConfiguration.Builder messagingConfigurationBuilder =
            WebSocketMessagingConfiguration.newBuilder()
                .jsonSchemaVersion(JsonSchemaVersion.V_2)
                .reconnectEnabled(false)
                .endpoint("wss://" + dittoEndpoint);

        final MessagingProvider messagingProvider =
            MessagingProviders.webSocket(messagingConfigurationBuilder.build(), AuthenticationProviders.basic(BasicAuthenticationConfiguration.newBuilder()
                .username("mqtt")
                .password("mqtt")
                .build()));

        DittoClient dittoClient = DittoClients.newInstance(messagingProvider);

        String thingId = namespace + ":" + twinId;

        logger.info("Using thingId: {}", thingId);

        ((ObjectNode) dittoJson).put("thingId", thingId);

        PutMethod createTwin = new PutMethod("https://" + dittoEndpoint + "api/2/things/" + thingId);
        createTwin.addRequestHeader("Authorization", "Basic bXF0dDptcXR0");
        createTwin.setRequestEntity(new StringRequestEntity(dittoJson.toPrettyString(), "application/json", null));
        client.executeMethod(createTwin);
        int statusCode = createTwin.getStatusCode();
        createTwin.releaseConnection();

        logger.info("Status was: {}", statusCode);

        if (statusCode > 299) {
            throw new IllegalStateException("Device does not exist and could not be created!");
        }

        // Fetch Mappings and initialize PLC4X Scraping
        logger.info("========================");
        logger.info("Configuration Properties");
        logger.info("========================");

        ArrayNode stereotypes = getConfigProperties(mappingJson);

        for (JsonNode stereotype : stereotypes) {
            String name = stereotype.get("name").asText();
            String type = stereotype.get("type").asText();

            logger.info("Property {} - {}", name, type);

            Optional<JsonNode> mappingAttributes = getStereotype(stereotype);

            if (mappingAttributes.isPresent()) {
                String address = mappingAttributes.get().get("address").asText();
                long rate = Long.parseLong(mappingAttributes.get().get("rate").asText());
                String url = mappingAttributes.get().get("url").asText();

                logger.info("Mapping {} - {} - {}", url, address, rate);

                // Start scraping
                executor.scheduleAtFixedRate(() -> {
                    try (PlcConnection connection = new PlcDriverManager().getConnection(url)) {
                        PlcReadResponse response = connection.readRequestBuilder()
                            .addItem(FIELD_NAME, address)
                            .build()
                            .execute()
                            .get(5, TimeUnit.SECONDS);

                        if (response.getResponseCode(FIELD_NAME) != PlcResponseCode.OK) {
                            logger.warn("Issue with fetching field value {}, got response {}", address, response.getResponseCode(FIELD_NAME));
                            return;
                        }

                        // Send the Value to Ditto
                        sendValueToDitto(dittoClient, thingId, name, type, response);
                    } catch (Exception e) {
                        logger.warn("Unable to connect to PLC4X / Execute request");
                    }
                }, rate, rate, TimeUnit.MILLISECONDS);

            } else {
                logger.info("No mapping given, will be ignored...");
            }
        }
    }

    private static void parseCommandLineParameter(String[] args) {
        CommandLineParser parser = new DefaultParser();

        // create the Options
        Options options = new Options();
        options.addOption(Option.builder()
            .longOpt("namespace")
            .desc("Vorto Namespace")
            .hasArg()
            .argName("NAMESPACE")
            .optionalArg(false)
            .type(String.class)
            .build());

        options.addOption(Option.builder()
            .longOpt("model-name")
            .desc("Vorto Model Name")
            .hasArg()
            .argName("MODEL")
            .optionalArg(false)
            .type(String.class)
            .build());

        options.addOption(Option.builder()
            .longOpt("model-version")
            .desc("Vorto Model Version")
            .hasArg()
            .argName("VERSION")
            .optionalArg(false)
            .type(String.class)
            .build());

        options.addOption(Option.builder()
            .longOpt("mapping")
            .desc("Vorto Mapping")
            .hasArg()
            .argName("MAPPING")
            .optionalArg(false)
            .type(String.class)
            .build());

        options.addOption(Option.builder()
            .longOpt("ditto-endpoint")
            .desc("Ditto Endpoint")
            .hasArg()
            .argName("ENDPOINT")
            .optionalArg(false)
            .type(String.class)
            .build());

        options.addOption(Option.builder()
            .longOpt("twin-id")
            .desc("Ditto Twin ID")
            .hasArg()
            .argName("TWIN_ID")
            .optionalArg(false)
            .type(String.class)
            .build());

        try {
            // automatically generate the help statement
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "Plc4XVortoDitto", options );

            // parse the command line arguments
            CommandLine line = parser.parse(options, args);

            namespace = line.getOptionValue("namespace", "org.apache.plc4x.examples");
            modelName = line.getOptionValue("model-name", "VirtualMachine");
            modelVersion = line.getOptionValue("model-version", "1.0.0");
            modelMapping = line.getOptionValue("mapping", "demoSpsPragmatics");
            dittoEndpoint = line.getOptionValue("ditto-endpoint", "twin.pragmaticindustries.de/");
            twinId = line.getOptionValue("twin-id", UUID.randomUUID().toString().replace("-", ""));

            logger.info("Namespace: {}", namespace);
            logger.info("Model Name: {}", modelName);
            logger.info("Model Version: {}", modelVersion);
            logger.info("Mapping: {}", modelMapping);
            logger.info("Ditto Endpoint: {}", dittoEndpoint);
            logger.info("Twin Id: {}", twinId);
            logger.info("Ditto Id: {}:{}", namespace, twinId);

        } catch (ParseException exp) {
            System.out.println("Unexpected exception:" + exp.getMessage());
            System.exit(1);
        }
    }

    private static String doHttpGet(HttpClient client, String s) throws IOException {
        GetMethod getMapping = new GetMethod(s);
        client.executeMethod(getMapping);
        String mappingJsonString = getMapping.getResponseBodyAsString();
        getMapping.releaseConnection();
        return mappingJsonString;
    }

    private static void sendValueToDitto(DittoClient dittoClient, String thingId, String name, String type, PlcReadResponse response) {
        String propertyPath = "configuration/" + name;
        // Fetch value from PLC4X Response
        if ("DOUBLE".equals(type)) {
            double value = response.getDouble(FIELD_NAME);
            sendInternal(dittoClient, thingId, feature -> feature.putProperty(propertyPath, value));
        } else if ("BOOLEAN".equals(type)) {
            boolean value = response.getBoolean(FIELD_NAME);
            sendInternal(dittoClient, thingId, feature -> feature.putProperty(propertyPath, value));
        } else if ("INT".equals(type)) {
            int value = response.getInteger(FIELD_NAME);
            sendInternal(dittoClient, thingId, feature -> feature.putProperty(propertyPath, value));
        } else {
            throw new NotImplementedException("Currently type " + type + " is not implemented!");
        }
    }

    private static <T> void sendInternal(DittoClient dittoClient, String thingId, Function<TwinFeatureHandle, CompletableFuture<Void>> setter) {
        TwinFeatureHandle twinFeatureHandle = dittoClient.twin()
            .forId(ThingId.of(thingId))
            .forFeature("VirtualMachine".toLowerCase());
        setter.apply(twinFeatureHandle)
            .handle(new BiFunction<Void, Throwable, Object>() {
                @Override
                public Object apply(Void aVoid, Throwable throwable) {
                    if (throwable != null) {
                        logger.info("Unable to send update to Ditto");
                        logger.warn("Unable to send update to Ditto", throwable);
                    } else {
                        logger.info("Sent update to Ditto!");
                    }
                    return null;
                }
            });
    }

    private static Optional<JsonNode> getStereotype(JsonNode mappingJson) {
        return Optional.ofNullable(mappingJson.get("stereotypes"))
            .map(json -> json.get(0))
            .map(json -> json.get("attributes"));
    }

    private static ArrayNode getConfigProperties(JsonNode mappingJson) {
        return mappingJson.get("models")
            .get("org.apache.plc4x.examples:VirtualMachine:1.0.0")
            .withArray("configurationProperties");
    }
}
