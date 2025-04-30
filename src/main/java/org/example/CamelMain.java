package org.example;


import org.apache.camel.main.Main;


public class CamelMain {
   public static void main(String[] args) throws Exception {
       // Create Camel Main instance
       Main main = new Main();
      
       // Register the FhirToDhis2Route
       main.configure().addRoutesBuilder(new FhirToDhis2Route());
       // Debug to confirm route registration
       System.out.println("Registering route: org.example.FhirToDhis2Route");
      
       // Run the Camel application
       main.run(args);
   }
}
