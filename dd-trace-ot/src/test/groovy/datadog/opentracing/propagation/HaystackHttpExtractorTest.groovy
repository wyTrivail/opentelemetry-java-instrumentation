package datadog.opentracing.propagation

import datadog.trace.api.sampling.PrioritySampling
import io.opentracing.SpanContext
import io.opentracing.propagation.TextMapExtractAdapter
import spock.lang.Specification

import static datadog.opentracing.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.opentracing.propagation.HaystackHttpCodec.SPAN_ID_KEY
import static datadog.opentracing.propagation.HaystackHttpCodec.TRACE_ID_KEY
import static datadog.opentracing.propagation.HttpCodec.UINT64_MAX

class HaystackHttpExtractorTest extends Specification {

  HttpCodec.Extractor extractor = new HaystackHttpCodec.Extractor(["SOME_HEADER": "some-tag"])

  def "extract http headers"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : traceId,
      (SPAN_ID_KEY.toUpperCase())             : spanId,
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    final ExtractedContext context = extractor.extract(new TextMapExtractAdapter(headers))

    then:
    context.traceId == traceId
    context.spanId == spanId
    context.baggage == ["k1": "v1", "k2": "v2"]
    context.tags == ["some-tag": "my-interesting-info"]
    context.samplingPriority == samplingPriority
    context.origin == origin

    where:
    traceId                        | spanId                         | samplingPriority              | origin
    "1"                            | "2"                            | PrioritySampling.SAMPLER_KEEP | null
    "2"                            | "3"                            | PrioritySampling.SAMPLER_KEEP | null
    UINT64_MAX.toString()          | UINT64_MAX.minus(1).toString() | PrioritySampling.SAMPLER_KEEP | null
    UINT64_MAX.minus(1).toString() | UINT64_MAX.toString()          | PrioritySampling.SAMPLER_KEEP | null
  }

  def "extract header tags with no propagation"() {
    when:
    TagContext context = extractor.extract(new TextMapExtractAdapter(headers))

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]


    where:
    headers                                                         | _
    [SOME_HEADER: "my-interesting-info"]                            | _
  }

  def "extract empty headers returns null"() {
    expect:
    extractor.extract(new TextMapExtractAdapter(["ignored-header": "ignored-value"])) == null
  }

  def "extract http headers with invalid non-numeric ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : "traceId",
      (SPAN_ID_KEY.toUpperCase())             : "spanId",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    SpanContext context = extractor.extract(new TextMapExtractAdapter(headers))

    then:
    context == null
  }

  def "extract http headers with out of range trace ID"() {
    setup:
    String outOfRangeTraceId = UINT64_MAX.add(BigInteger.ONE).toString()
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : outOfRangeTraceId,
      (SPAN_ID_KEY.toUpperCase())             : "0",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    SpanContext context = extractor.extract(new TextMapExtractAdapter(headers))

    then:
    context == null
  }

  def "extract http headers with out of range span ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : "0",
      (SPAN_ID_KEY.toUpperCase())             : "-1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    SpanContext context = extractor.extract(new TextMapExtractAdapter(headers))

    then:
    context == null
  }

  def "more ID range validation"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): traceId,
      (SPAN_ID_KEY.toUpperCase()) : spanId,
    ]

    when:
    final ExtractedContext context = extractor.extract(new TextMapExtractAdapter(headers))

    then:
    if (expectedTraceId) {
      assert context.traceId == expectedTraceId
      assert context.spanId == expectedSpanId
    } else {
      assert context == null
    }

    where:
    gtTraceId               | gSpanId                 | expectedTraceId | expectedSpanId
    "-1"                    | "1"                     | null            | "0"
    "1"                     | "-1"                    | null            | "0"
    "0"                     | "1"                     | null            | "0"
    "1"                     | "0"                     | "1"             | "0"
    "$UINT64_MAX"           | "1"                     | "$UINT64_MAX"   | "1"
    "${UINT64_MAX.plus(1)}" | "1"                     | null            | "1"
    "1"                     | "$UINT64_MAX"           | "1"             | "$UINT64_MAX"
    "1"                     | "${UINT64_MAX.plus(1)}" | null            | "0"

    traceId = gtTraceId.toString()
    spanId = gSpanId.toString()
  }
}