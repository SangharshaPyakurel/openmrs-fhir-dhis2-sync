# openmrs-fhir-dhis2-sync


To run this : Use command:

mvn clean install

mvn compile exec:java -Dexec.mainClass=org.example.CamelMain

In this repository, it is difficult to make changes according to DHIS2 organization units and different IDs. For now, it works, but making changes is hard. I plan to make it easier by using a configuration file. I encountered an error while setting up the configuration file. I'm currently working on this, and although it's working, the setup process is still difficult.
