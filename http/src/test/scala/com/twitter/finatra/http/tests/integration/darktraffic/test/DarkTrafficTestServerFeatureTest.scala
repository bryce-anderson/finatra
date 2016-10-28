package com.twitter.finatra.http.tests.integration.darktraffic.test

import com.google.inject.testing.fieldbinder.Bind
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.http.Status._
import com.twitter.finatra.annotations.DarkTrafficService
import com.twitter.finatra.http.{EmbeddedHttpServer, HttpHeaders}
import com.twitter.finatra.http.tests.integration.darktraffic.main.DarkTrafficTestServer
import com.twitter.inject.Mockito
import com.twitter.inject.server.{FeatureTest, PortUtils}
import com.twitter.util.Future
import org.mockito.ArgumentCaptor

class DarkTrafficTestServerFeatureTest extends FeatureTest with Mockito {

  @Bind
  @DarkTrafficService
  val darkTrafficService: Option[Service[Request, Response]] = Some(smartMock[Service[Request, Response]])

  darkTrafficService.get.apply(any[Request]).returns(Future.value(smartMock[Response]))

  // receive dark traffic service
  override val server = new EmbeddedHttpServer(
    twitterServer = new DarkTrafficTestServer {
      override val name = "dark-server"
    })

  lazy val liveServer = new EmbeddedHttpServer(
    twitterServer = new DarkTrafficTestServer {
      override val name = "live-server"
    },
    flags = Map(
      "http.dark.service.dest" -> s"/$$/inet/${PortUtils.loopbackAddress}/${server.httpExternalPort}"))

  "DarkTrafficServer" should {

    // Canonical-Resource header is used by Diffy Proxy
    "have Canonical-Resource header correctly set" in {
      server.httpGet(
        "/plaintext",
        withBody = "Hello, World!",
        andExpect = Ok)

      val captor = ArgumentCaptor.forClass(classOf[Request])
      there was one(darkTrafficService.get).apply(captor.capture())
      val request = captor.getValue
      request.headerMap(HttpHeaders.CanonicalResource) should be("GET_/plaintext")
    }

    // See SampleDarkTrafficFilterModule#enableSampling
    "Get method is forwarded" in {
      liveServer.httpGet(
        "/plaintext",
        withBody = "Hello, World!")

      // service stats
      liveServer.assertCounter("route/plaintext/GET/status/200", 1)

      // darkTrafficFilter stats
      liveServer.assertCounter("dark_traffic_filter/forwarded", 1)
      liveServer.assertCounter("dark_traffic_filter/skipped", 0)

      server.assertHealthy() // stat to be recorded on the dark service
      // "dark" service stats
      server.assertCounter("route/plaintext/GET/status/200", 1)
    }

    "Put method is forwarded" in {
      liveServer.httpPut(
        "/echo",
        putBody = "",
        andExpect = Ok,
        withBody = ""
      )

      // service stats
      liveServer.assertCounter("route/echo/PUT/status/200", 1)

      // darkTrafficFilter stats
      liveServer.assertCounter("dark_traffic_filter/forwarded", 1)
      liveServer.assertCounter("dark_traffic_filter/skipped", 0)

      server.assertHealthy() // stat to be recorded on the dark service
      // "dark" service stats
      server.assertCounter("route/echo/PUT/status/200", 1)
    }

    "Post method not forwarded" in {
      liveServer.httpPost(
        "/foo",
        postBody = "",
        andExpect = Ok,
        withBody = "bar")

      // service stats
      liveServer.assertCounter("route/foo/POST/status/200", 1)

      // darkTrafficFilter stats
      liveServer.assertCounter("dark_traffic_filter/forwarded", 0)
      liveServer.assertCounter("dark_traffic_filter/skipped", 1)

      server.assertHealthy() // stat to be recorded on the dark service
      // "dark" service stats
      server.assertCounter("route/foo/POST/status/200", 0)
    }

    "Delete method not forwarded" in {
      liveServer.httpDelete(
        "/delete",
        andExpect = Ok,
        withBody = "delete")

      // service stats
      liveServer.assertCounter("route/delete/DELETE/status/200", 1)

      // darkTrafficFilter stats
      liveServer.assertCounter("dark_traffic_filter/forwarded", 0)
      liveServer.assertCounter("dark_traffic_filter/skipped", 1)

      server.assertHealthy() // stat to be recorded on the dark service
      // "dark" service stats
      server.assertCounter("route/delete/DELETE/status/200", 0)
    }
  }

  override def beforeEach(): Unit = {
    liveServer.clearStats()
    server.clearStats()
  }

  override def afterAll(): Unit = {
    liveServer.close()
    server.close()
  }
}