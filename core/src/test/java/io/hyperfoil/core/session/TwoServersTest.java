package io.hyperfoil.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.hyperfoil.api.http.HttpMethod;
import io.hyperfoil.api.session.Session;
import io.hyperfoil.api.statistics.StatisticsSnapshot;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;

@RunWith(VertxUnitRunner.class)
public class TwoServersTest extends BaseScenarioTest {
   CountDownLatch latch = new CountDownLatch(1);

   @Override
   protected void initRouter() {
      router.route("/test").handler(ctx -> {
         try {
            latch.await(10, TimeUnit.SECONDS);
         } catch (InterruptedException e) {
         }
         ctx.response().setStatusCode(200).end();
      });
   }

   @Override
   public void before(TestContext ctx) {
      super.before(ctx);
      Router secondRouter = Router.router(vertx);
      secondRouter.route("/test").handler(context -> context.response().setStatusCode(300).end());
      vertx.createHttpServer().requestHandler(secondRouter::accept)
            .listen(8081, "localhost", ctx.asyncAssertSuccess());
      benchmarkBuilder.simulation()
            .http().baseUrl("http://localhost:8080").endHttp()
            .http("http://localhost:8081").endHttp();
   }

   @Test
   public void test() {
      scenario().initialSequence("test")
            .step().httpRequest(HttpMethod.GET).path("/test").endStep()
            .step().httpRequest(HttpMethod.GET)
               .baseUrl("http://localhost:8081")
               .path("/test")
               .handler()
                  .onCompletion(s -> latch.countDown())
               .endHandler()
            .endStep()
            .step().awaitAllResponses();

      List<Session> sessions = runScenario();
      StatisticsSnapshot stats = assertSingleSessionStats(sessions);
      assertThat(stats.status_2xx).isEqualTo(1);
      assertThat(stats.status_3xx).isEqualTo(1);
   }
}