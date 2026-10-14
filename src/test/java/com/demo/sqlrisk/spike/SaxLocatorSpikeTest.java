package com.demo.sqlrisk.spike;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T0.3 Spike A：验证 JDK SAX 的 Locator 行号语义可支撑"按行切片语句原文"（DD-3），
 * 以及 MyBatis DOCTYPE 在离线环境下的可解析性（禁外部实体 + 空 EntityResolver）。
 *
 * <p>结论落地：M5 的 MapperXmlSaxHandler 采用同一套解析配置与行号口径。</p>
 */
class SaxLocatorSpikeTest {

    private static final String XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<mapper namespace=\"com.demo.OrderMapper\">\n"
                    + "  <select id=\"s1\">\n"
                    + "    SELECT 1\n"
                    + "  </select>\n"
                    + "  <update id=\"u1\">UPDATE t SET a=1</update>\n"
                    + "</mapper>";

    @Test
    void start_and_end_line_numbers_support_raw_slice() throws Exception {
        final Map<String, int[]> spans = new LinkedHashMap<String, int[]>();
        newSaxParser().parse(new InputSource(new StringReader(XML)), new DefaultHandler() {
            private Locator locator;

            @Override
            public void setDocumentLocator(Locator l) {
                this.locator = l;
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attrs) {
                if ("mapper".equals(qName) || "select".equals(qName) || "update".equals(qName)) {
                    spans.put(qName, new int[]{locator.getLineNumber(), -1});
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if (spans.containsKey(qName)) {
                    int[] span = spans.get(qName);
                    if (span[1] == -1) {
                        span[1] = locator.getLineNumber();
                    }
                }
            }
        });

        assertArrayEquals(new int[]{2, 7}, spans.get("mapper"));
        assertArrayEquals(new int[]{3, 5}, spans.get("select"), "select 起止行应为 3~5（含闭合标签行）");
        assertArrayEquals(new int[]{6, 6}, spans.get("update"));
    }

    @Test
    void mybatis_doctype_parses_offline() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\""
                + " \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
                + "<mapper namespace=\"com.demo.OrderMapper\">\n"
                + "  <select id=\"s1\">SELECT 1</select>\n"
                + "</mapper>";
        final int[] elementCount = {0};
        newSaxParser().parse(new InputSource(new StringReader(xml)), new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attrs) {
                elementCount[0]++;
            }
        });
        assertEquals(2, elementCount[0], "离线解析含 MyBatis DTD 声明的 mapper 应成功且不访问网络");
    }

    private static SAXParser newSaxParser() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        SAXParser parser = factory.newSAXParser();
        parser.getXMLReader().setFeature("http://xml.org/sax/features/external-general-entities", false);
        parser.getXMLReader().setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        parser.getXMLReader().setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        parser.getXMLReader().setEntityResolver(
                (publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
        return parser;
    }
}
