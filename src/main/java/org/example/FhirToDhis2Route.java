package org.example;

import ca.uhn.fhir.context.FhirContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class FhirToDhis2Route extends RouteBuilder {

    private final FhirContext fhirContext = FhirContext.forR4();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String openmrsAuth = "Basic " + Base64.getEncoder().encodeToString("admin:Admin123".getBytes());
    private final String dhis2Auth = "Basic " + Base64.getEncoder().encodeToString("admin:district".getBytes());

    @Override
    public void configure() throws Exception {

        // Step 1: Fetch Patients from OpenMRS → Save to HAPI FHIR
        from("timer:fetchOpenMRSData?period=30000")
            .setHeader(Exchange.HTTP_METHOD, constant("GET"))
            .setHeader("Authorization", constant(openmrsAuth))
            .to("http://localhost:80/openmrs/ws/fhir2/R4/Patient")
            .process(exchange -> {
                String fhirData = exchange.getIn().getBody(String.class);
                System.out.println("Fetched FHIR Patient Data: " + fhirData);
                Bundle bundle = fhirContext.newJsonParser().parseResource(Bundle.class, fhirData);
                exchange.getIn().setBody(bundle);
            })
            .split(simple("${body.entry}"))
            .process(exchange -> {
                Bundle.BundleEntryComponent entry = exchange.getIn().getBody(Bundle.BundleEntryComponent.class);
                Patient patient = (Patient) entry.getResource();
                String patientJson = fhirContext.newJsonParser().encodeResourceToString(patient);
                exchange.getIn().setBody(patientJson);
            })
            .setHeader(Exchange.HTTP_METHOD, constant("POST"))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/fhir+json"))
            .to("http://localhost:8086/fhir/Patient")
            .process(exchange -> {
                System.out.println("Stored Patient in HAPI FHIR: " + exchange.getIn().getBody(String.class));
            });

        // Step 2: Fetch Patients from HAPI FHIR → Send to DHIS2
        from("timer:fetchHapiFHIRData?period=40000")
            .setHeader(Exchange.HTTP_METHOD, constant("GET"))
            .setHeader("Authorization", constant(openmrsAuth))
            .to("http://localhost:8086/fhir/Patient")
            .process(exchange -> {
                String fhirData = exchange.getIn().getBody(String.class);
                Bundle bundle = fhirContext.newJsonParser().parseResource(Bundle.class, fhirData);
                exchange.getIn().setBody(bundle);
            })
            .split(simple("${body.entry}"))
            .process(exchange -> {
                Bundle.BundleEntryComponent entry = exchange.getIn().getBody(Bundle.BundleEntryComponent.class);
                Patient patient = (Patient) entry.getResource();

                String identifier = patient.getIdentifierFirstRep().getValue();
                String gender = patient.getGender() != null ? patient.getGender().toCode().toUpperCase() : "UNKNOWN";
                HumanName name = patient.getNameFirstRep();
                String fullName = (name.getGivenAsSingleString() + " " + name.getFamily()).trim();

                // Map gender
                String genderCode = "3";
                if ("MALE".equals(gender)) {
                    genderCode = "1";
                } else if ("FEMALE".equals(gender)) {
                    genderCode = "2";
                }

                // Build DHIS2 TEI JSON
                ObjectNode teiJson = mapper.createObjectNode();
                teiJson.put("trackedEntityType", "alAJ5kvWfY5");
                teiJson.put("orgUnit", "jGZ8gIWGHW6");

                ArrayNode attributes = mapper.createArrayNode();
                attributes.add(createAttribute("yKDkQtKsE0Z", identifier)); // Patient Identifier
                attributes.add(createAttribute("njG0C3ZWeXw", fullName));
                attributes.add(createAttribute("wiBN2dBprgv", genderCode));
                teiJson.set("attributes", attributes);

                exchange.setProperty("patientIdentifier", identifier);
                exchange.setProperty("teiJsonString", teiJson.toString());
                exchange.setProperty("fullTeiJson", teiJson); // Store full JSON
            })
            .to("direct:sendToDHIS2");

        // Step 3: Send to DHIS2
        from("direct:sendToDHIS2")
            .doTry()
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("Authorization", constant(dhis2Auth))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .setHeader("Accept", constant("application/json"))
                .removeHeaders("CamelHttp*")
                .toD("http://localhost:8082/api/trackedEntityInstances?filter=yKDkQtKsE0Z:EQ:${exchangeProperty.patientIdentifier}&paging=false&fields=trackedEntityInstance,deleted,attributes&trackedEntityType=alAJ5kvWfY5&ou=jGZ8gIWGHW6")
                .process(exchange -> {
                    String response = exchange.getIn().getBody(String.class);
                    System.out.println("GET TEI Response: " + response);
                    ObjectNode json = (ObjectNode) mapper.readTree(response);
                    ArrayNode instances = (ArrayNode) json.get("trackedEntityInstances");

                    if (instances != null && instances.size() > 0) {
                        ObjectNode tei = (ObjectNode) instances.get(0);
                        String teiId = tei.get("trackedEntityInstance").asText();
                        boolean isDeleted = tei.has("deleted") && tei.get("deleted").asBoolean();
                        exchange.setProperty("teiId", teiId);
                        exchange.setProperty("teiDeleted", isDeleted);
                        exchange.setProperty("existingTei", tei);
                        System.out.println("Existing TEI found: ID=" + teiId + ", Deleted=" + isDeleted);
                    } else {
                        exchange.setProperty("teiId", null);
                        exchange.setProperty("teiDeleted", false);
                        exchange.setProperty("existingTei", null);
                        System.out.println("No TEI found for identifier: " + exchange.getProperty("patientIdentifier"));
                    }
                })
                .doCatch(Exception.class)
                .process(exchange -> {
                    Exception e = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                    System.out.println("Error fetching/updating TEI: " + e.getMessage());
                    // Log the response body if available
                    String responseBody = exchange.getIn().getBody(String.class);
                    System.out.println("DHIS2 Error Response: " + responseBody);
                    exchange.setProperty("teiId", null);
                    exchange.setProperty("teiDeleted", false);
                    exchange.setProperty("existingTei", null);
                })
            .end()
            .choice()
                .when(simple("${exchangeProperty.teiId} != null && ${exchangeProperty.teiDeleted} == false"))
                    .log("Updating existing TEI: ${exchangeProperty.teiId}")
                    .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
                    .setHeader("Authorization", constant(dhis2Auth))
                    .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                    .process(exchange -> {
                        ObjectNode newTeiJson = (ObjectNode) exchange.getProperty("fullTeiJson");
                        ObjectNode existingTei = (ObjectNode) exchange.getProperty("existingTei");

                        // 1. Preserve the TEI ID
                        newTeiJson.put("trackedEntityInstance", existingTei.get("trackedEntityInstance").asText());

                        // 2.  Build a map of existing attributes for efficient lookup.
                        Map<String, ObjectNode> existingAttributesMap = new HashMap<>();
                        ArrayNode existingAttributes = (ArrayNode) existingTei.get("attributes");
                        for (int i = 0; i < existingAttributes.size(); i++) {
                            ObjectNode existingAttribute = (ObjectNode) existingAttributes.get(i);
                            existingAttributesMap.put(existingAttribute.get("attribute").asText(), existingAttribute);
                        }

                        // 3.  Create a new array for the updated attributes, preserving order.
                        ArrayNode updatedAttributes = mapper.createArrayNode();
                        for (int i = 0; i < newTeiJson.get("attributes").size(); i++) {
                            ObjectNode newAttribute = (ObjectNode) newTeiJson.get("attributes").get(i);
                            String attributeId = newAttribute.get("attribute").asText();
                            if (existingAttributesMap.containsKey(attributeId)) {
                                updatedAttributes.add(newAttribute); // Use the new value
                                existingAttributesMap.remove(attributeId); // Remove it from the map
                            }
                            else{
                                updatedAttributes.add(newAttribute);
                            }
                        }
                        // 4.  Add any remaining attributes from the existing TEI data.
                        for (ObjectNode remainingAttribute : existingAttributesMap.values()) {
                            updatedAttributes.add(remainingAttribute);
                        }
                        newTeiJson.set("attributes", updatedAttributes);
                        exchange.getIn().setBody(newTeiJson.toString());
                        exchange.setProperty("teiJsonString", newTeiJson.toString());
                        System.out.println("PUT Request Body: " + exchange.getIn().getBody(String.class));

                    })
                    .toD("http://localhost:8082/api/trackedEntityInstances/${exchangeProperty.teiId}")
                    .log("Updated TEI: ${exchangeProperty.teiId}")
                .otherwise()
                    .log("Creating new TEI for identifier: ${exchangeProperty.patientIdentifier}")
                    .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                    .setHeader("Authorization", constant(dhis2Auth))
                    .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                    .setBody(simple("${exchangeProperty.teiJsonString}"))
                    .to("http://localhost:8082/api/trackedEntityInstances")
                    .log("Created TEI for identifier: ${exchangeProperty.patientIdentifier}")
                .end()
            .end();
    }

    private ObjectNode createAttribute(String attributeId, String value) {
        ObjectNode attr = mapper.createObjectNode();
        attr.put("attribute", attributeId);
        attr.put("value", value);
        return attr;
    }
}
