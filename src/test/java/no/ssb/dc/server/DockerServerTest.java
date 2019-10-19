package no.ssb.dc.server;

import no.ssb.dc.api.Specification;
import no.ssb.dc.api.node.builder.SpecificationBuilder;
import no.ssb.dc.api.util.CommonUtils;
import no.ssb.dc.core.executor.Worker;
import no.ssb.dc.test.client.TestClient;
import no.ssb.dc.test.server.TestServer;
import no.ssb.dc.test.server.TestServerListener;
import org.testng.annotations.Ignore;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.util.Map;

import static no.ssb.dc.api.Builders.addContent;
import static no.ssb.dc.api.Builders.context;
import static no.ssb.dc.api.Builders.execute;
import static no.ssb.dc.api.Builders.get;
import static no.ssb.dc.api.Builders.nextPage;
import static no.ssb.dc.api.Builders.paginate;
import static no.ssb.dc.api.Builders.parallel;
import static no.ssb.dc.api.Builders.publish;
import static no.ssb.dc.api.Builders.regex;
import static no.ssb.dc.api.Builders.sequence;
import static no.ssb.dc.api.Builders.status;
import static no.ssb.dc.api.Builders.whenVariableIsNull;
import static no.ssb.dc.api.Builders.xpath;

@Listeners(TestServerListener.class)
public class DockerServerTest {

    @Inject
    TestServer server;

    @Inject
    TestClient client;

    @Test
    public void testPingServer() {
        client.get("/ping").expect200Ok();
    }

    @Test
    public void testMockServer() {
        client.get("/mock").expect200Ok();
    }

    @Test
    public void testPutTask() throws InterruptedException {
        String spec = CommonUtils.readFileOrClasspathResource("worker.config/page-test.json").replace("PORT", Integer.valueOf(server.getTestServerServicePort()).toString());
        client.put("/task", spec).expect201Created();
        Thread.sleep(3000);
    }

    @Ignore
    @Test
    public void ReadmeExample() {
        SpecificationBuilder feedBuilder = Specification.start("", "loop")
                .configure(context()
                        .topic("topic")
                        .variable("nextPosition", "${contentStream.hasLastPosition() ? contentStream.lastPosition() : 1}")
                )
                .function(paginate("loop")
                        .variable("fromPosition", "${nextPosition}")
                        .addPageContent()
                        .iterate(execute("page"))
                        .prefetchThreshold(15)
                        .until(whenVariableIsNull("nextPosition"))
                )
                .function(get("page")
                        .url("http://example.com/feed?pos=${fromPosition}&size=10")
                        .validate(status().success(200, 299))
                        .pipe(sequence(xpath("/feed/entry"))
                                .expected(xpath("/entry/id"))
                        )
                        .pipe(nextPage()
                                .output("nextPosition", regex(xpath("/feed/link[@rel=\"next\"]/@href"), "(?<=[?&]pos=)[^&]*"))
                        )
                        .pipe(parallel(xpath("/feed/entry"))
                                .variable("position", xpath("/entry/id"))
                                .pipe(addContent("${position}", "entry"))
                                .pipe(publish("${position}"))
                        )
                        .returnVariables("nextPosition")
                );

        Worker.newBuilder()
                .configuration(Map.of(
                        "content.stream.connector", "rawdata",
                        "rawdata.client.provider", "memory")
                )
                .specification(feedBuilder)
                .printExecutionPlan()
                .printConfiguration()
                .build()
                .run();
    }
}