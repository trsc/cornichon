package com.github.agourlay.cornichon.http

import akka.http.scaladsl.model.{ HttpHeader, StatusCode }
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.Uri.Query
import cats.data.Xor
import cats.data.Xor.{ left, right }
import com.github.agourlay.cornichon.core._
import com.github.agourlay.cornichon.http.client.HttpClient
import com.github.agourlay.cornichon.json.CornichonJson
import org.json4s._

import scala.concurrent.duration._

class HttpService(baseUrl: String, requestTimeout: FiniteDuration, client: HttpClient, resolver: Resolver) extends CornichonJson {

  import HttpService._

  private type WithPayloadCall = (JValue, String, Seq[(String, String)], Seq[HttpHeader], FiniteDuration) ⇒ Xor[HttpError, CornichonHttpResponse]
  private type WithoutPayloadCall = (String, Seq[(String, String)], Seq[HttpHeader], FiniteDuration) ⇒ Xor[HttpError, CornichonHttpResponse]

  private def withPayload(call: WithPayloadCall, payload: String, url: String, params: Seq[(String, String)],
    headers: Seq[(String, String)], extractor: Option[String], requestTimeout: FiniteDuration, expect: Option[Int])(s: Session) =
    for {
      payloadResolved ← resolver.fillPlaceholders(payload)(s)
      json ← parseJsonXor(payloadResolved)
      r ← resolveCommonRequestParts(url, params, headers)(s)
      resp ← call(json, r._1, r._2, r._3, requestTimeout)
      newSession ← handleResponse(resp, expect, extractor)(s)
    } yield {
      (resp, newSession)
    }

  private def withoutPayload(call: WithoutPayloadCall, url: String, params: Seq[(String, String)],
    headers: Seq[(String, String)], extractor: Option[String], requestTimeout: FiniteDuration, expect: Option[Int])(s: Session) =
    for {
      r ← resolveCommonRequestParts(url, params, headers)(s)
      resp ← call(r._1, r._2, r._3, requestTimeout)
      newSession ← handleResponse(resp, expect, extractor)(s)
    } yield {
      (resp, newSession)
    }

  private def handleResponse(resp: CornichonHttpResponse, expect: Option[Int], extractor: Option[String])(session: Session) =
    for {
      resExpected ← expectStatusCode(resp, expect)
      newSession = fillInSessionWithResponse(session, resp, extractor)
    } yield {
      newSession
    }

  def resolveCommonRequestParts(url: String, params: Seq[(String, String)], headers: Seq[(String, String)])(s: Session) =
    for {
      urlResolved ← resolver.fillPlaceholders(withBaseUrl(url))(s)
      paramsResolved ← resolveParams(url, params)(s)
      headersResolved ← resolver.tuplesResolver(headers, s)
      parsedHeaders ← parseHttpHeaders(headersResolved)
      extractedHeaders ← extractWithHeadersSession(s)
    } yield {
      (urlResolved, paramsResolved, parsedHeaders ++ extractedHeaders)
    }

  private def expectStatusCode(httpResponse: CornichonHttpResponse, expected: Option[Int]): Xor[CornichonError, CornichonHttpResponse] =
    expected.fold[Xor[CornichonError, CornichonHttpResponse]](right(httpResponse)) { e ⇒
      if (httpResponse.status == StatusCode.int2StatusCode(e))
        right(httpResponse)
      else
        left(StatusNonExpected(e, httpResponse))
    }

  def resolveParams(url: String, params: Seq[(String, String)])(session: Session): Xor[CornichonError, Seq[(String, String)]] = {
    val urlsParamsPart = url.dropWhile(_ != '?').drop(1)
    val urlParams = if (urlsParamsPart.trim.isEmpty) Map.empty else Query.apply(urlsParamsPart).toMap
    resolver.tuplesResolver(urlParams.toSeq ++ params, session)
  }

  def Post(url: String, payload: String, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None, expected: Option[Int] = None)(s: Session) =
    withPayload(client.postJson, payload, url, params, headers, extractor, requestTimeout, expected)(s).fold(e ⇒ throw e, _._2)

  def Put(url: String, payload: String, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None, expected: Option[Int] = None)(s: Session): Session =
    withPayload(client.putJson, payload, url, params, headers, extractor, requestTimeout, expected)(s).fold(e ⇒ throw e, _._2)

  def Patch(url: String, payload: String, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None, expected: Option[Int] = None)(s: Session): Session =
    withPayload(client.patchJson, payload, url, params, headers, extractor, requestTimeout, expected)(s).fold(e ⇒ throw e, _._2)

  def Get(url: String, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None, expected: Option[Int] = None)(s: Session): Session =
    withoutPayload(client.getJson, url, params, headers, extractor, requestTimeout, expected)(s).fold(e ⇒ throw e, _._2)

  def Head(url: String, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None, expected: Option[Int] = None)(s: Session): Session =
    withoutPayload(client.headJson, url, params, headers, extractor, requestTimeout, expected)(s).fold(e ⇒ throw e, _._2)

  def Options(url: String, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None, expected: Option[Int] = None)(s: Session): Session =
    withoutPayload(client.optionsJson, url, params, headers, extractor, requestTimeout, expected)(s).fold(e ⇒ throw e, _._2)

  def Delete(url: String, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None, expected: Option[Int] = None)(s: Session): Session =
    withoutPayload(client.deleteJson, url, params, headers, extractor, requestTimeout, expected)(s).fold(e ⇒ throw e, _._2)

  def OpenSSE(url: String, takeWithin: FiniteDuration, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None)(s: Session) =
    withoutPayload(client.openSSE, url, params, headers, extractor, takeWithin, None)(s).fold(e ⇒ throw e, _._2)

  def OpenWS(url: String, takeWithin: FiniteDuration, params: Seq[(String, String)], headers: Seq[(String, String)], extractor: Option[String] = None)(s: Session) =
    withoutPayload(client.openWS, url, params, headers, extractor, takeWithin, None)(s).fold(e ⇒ throw e, _._2)

  def fillInSessionWithResponse(session: Session, response: CornichonHttpResponse, extractor: Option[String]): Session =
    extractor.fold(session) { e ⇒
      session.addValue(e, response.body)
    }.addValues(Seq(
      LastResponseStatusKey → response.status.intValue().toString,
      LastResponseBodyKey → response.body,
      LastResponseHeadersKey → response.headers.map(h ⇒ s"${h.name()}$HeadersKeyValueDelim${h.value()}").mkString(",")
    ))

  def parseHttpHeaders(headers: Seq[(String, String)]): Xor[MalformedHeadersError, Seq[HttpHeader]] = {
    @scala.annotation.tailrec
    def loop(headers: Seq[(String, String)], acc: Seq[HttpHeader]): Xor[MalformedHeadersError, Seq[HttpHeader]] = {
      if (headers.isEmpty) right(acc)
      else {
        val (name, value) = headers.head
        HttpHeader.parse(name, value) match {
          case ParsingResult.Ok(h, e) ⇒ loop(headers.tail, acc :+ h)
          case ParsingResult.Error(e) ⇒ left(MalformedHeadersError(e.formatPretty))
        }
      }
    }
    loop(headers, Seq.empty[HttpHeader])
  }

  def extractWithHeadersSession(session: Session): Xor[MalformedHeadersError, Seq[HttpHeader]] =
    session.getOpt(WithHeadersKey).fold[Xor[MalformedHeadersError, Seq[HttpHeader]]](right(Seq.empty[HttpHeader])) { headers ⇒
      val tuples = headers.split(',').toSeq.map { header ⇒
        val elms = header.split(HeadersKeyValueDelim)
        (elms.head, elms.tail.head)
      }
      parseHttpHeaders(tuples)
    }

  private def withBaseUrl(input: String) = if (baseUrl.isEmpty) input else baseUrl + input
}

object HttpService {
  val LastResponseBodyKey = "last-response-body"
  val LastResponseStatusKey = "last-response-status"
  val LastResponseHeadersKey = "last-response-headers"
  val WithHeadersKey = "with-headers"
  val HeadersKeyValueDelim = '|'
}