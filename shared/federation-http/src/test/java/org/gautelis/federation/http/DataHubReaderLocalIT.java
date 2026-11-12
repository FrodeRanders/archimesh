package org.gautelis.federation.http;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.gautelis.federation.Identity.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit read-only check against a disposable seeded OSS demo, never enabled by ordinary tests. */
class DataHubReaderLocalIT {
    @Test void pinnedOssDatasetProductAndApplicationAspectsMatchTheReaderContract() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_FEDERATION_DATAHUB_IT")));
        var repository=new Repository("datahub","demo-reader-probe");
        try (var reader=new DataHubReader(repository,URI.create(System.getenv("FEDERATION_DATAHUB_URL")),
                () -> System.getenv("FEDERATION_DATAHUB_TOKEN"),Duration.ofSeconds(10),2_097_152,false)) {
            for (var entry:List.of(
                    List.of("dataset","urn:li:dataset:(urn:li:dataPlatform:postgres,northstar_demo.oms.sales_order,DEV)","datasetProperties","Sales order headers"),
                    List.of("dataProduct","urn:li:dataProduct:northstar-demo-order-book","dataProductProperties","Trade Order Book"),
                    List.of("application","urn:li:application:northstar-demo-oms","applicationProperties","Northstar Order Management"))) {
                var representation=new Representation(new Resource(repository,ResourceKind.OBJECT,entry.get(1)),entry.getFirst());
                var name=new SourceReader.Field(entry.get(2),"/name"); var description=new SourceReader.Field(entry.get(2),"/description");
                var snapshot=reader.read(representation,Set.of(name,description));
                assertEquals(entry.get(3),snapshot.fields().get(name).value().asText());
                assertEquals(SourceReader.Lifecycle.ACTIVE,snapshot.lifecycle());
                assertEquals(snapshot.digest(),reader.read(representation,Set.of(description,name)).digest());
            }
        }
    }
}
