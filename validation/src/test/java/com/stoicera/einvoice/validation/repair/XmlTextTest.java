package com.stoicera.einvoice.validation.repair;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class XmlTextTest {

  @Test
  void spansSurviveCommentsCdataInstructionsAndQuotedAngleBrackets() {
    String xml =
        "<?xml version=\"1.0\"?>\n<!-- <x> -->\n<a q=\"1>2\" r='a/'><?pi <y>?><b><![CDATA[<z>]]></b><c/></a>";
    XmlText doc = XmlText.of(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();
    Element a = doc.dom().getDocumentElement();
    Element b = (Element) a.getElementsByTagName("b").item(0);
    Element c = (Element) a.getElementsByTagName("c").item(0);

    XmlText.Span spanA = doc.span(a);
    assertThat(xml.substring(spanA.startTagStart(), spanA.startTagEnd()))
        .isEqualTo("<a q=\"1>2\" r='a/'>");
    assertThat(xml.substring(spanA.endTagStart(), spanA.endTagEnd())).isEqualTo("</a>");
    XmlText.Span spanB = doc.span(b);
    assertThat(xml.substring(spanB.startTagEnd(), spanB.endTagStart()))
        .isEqualTo("<![CDATA[<z>]]>");
    assertThat(doc.span(c).isEmptyTag()).isTrue();
    assertThat(doc.span(c).qname()).isEqualTo("c");
  }

  @Test
  void aByteOrderMarkAndALatin1DocumentRoundTripExactly() {
    byte[] bom = "﻿<a>ä</a>".getBytes(StandardCharsets.UTF_8);
    XmlText withBom = XmlText.of(bom).orElseThrow();
    assertThat(withBom.encode(withBom.apply(List.of()))).isEqualTo(bom);

    byte[] latin1 =
        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>ä</a>"
            .getBytes(StandardCharsets.ISO_8859_1);
    XmlText doc = XmlText.of(latin1).orElseThrow();
    String corrected = doc.apply(List.of(TextEdit.insert(doc.text().indexOf("</a>"), "ö")));
    assertThat(doc.encode(corrected))
        .isEqualTo(
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>äö</a>"
                .getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void notWellFormedOrDoctypeIsNoIndex() {
    assertThat(XmlText.of("<a>".getBytes(StandardCharsets.UTF_8))).isEmpty();
    assertThat(
            XmlText.of(
                "<!DOCTYPE a [<!ENTITY x \"y\">]><a>&x;</a>".getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
  }

  @Test
  void indentationIsReadFromTheLine() {
    String xml = "<a>\n  <b/>\n\t<c/><d/>\n</a>";
    XmlText doc = XmlText.of(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();

    assertThat(doc.indentBefore(xml.indexOf("<b/>"))).contains("  ");
    assertThat(doc.indentBefore(xml.indexOf("<c/>"))).contains("\t");
    assertThat(doc.indentBefore(xml.indexOf("<d/>"))).isEmpty();
    assertThat(doc.lineBreak()).isEqualTo("\n");
  }
}
