package com.demo.sqlrisk.util;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;

import java.io.ByteArrayInputStream;
import java.io.StringReader;

/**
 * 安全 SAX 解析共用件（详细设计 §9.2/§10.2）：JDK SAX（决策 D3）与 XXE 加固
 * （禁外部实体 / 禁外部 DTD 加载，MyBatis DTD 由空内容 EntityResolver 兜底，离线解析）。
 */
public final class XmlSupport {

    private XmlSupport() {
    }

    /** 解析 XML 内容；结构错误统一包装为 {@link XmlParseException}（调用方按文件/语句隔离）。 */
    public static void parse(String content, DefaultHandler handler) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            reader.setEntityResolver(
                    (publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
            reader.setErrorHandler(handler);
            reader.setContentHandler(handler);
            reader.parse(new InputSource(new StringReader(content)));
        } catch (Exception e) {
            throw new XmlParseException("XML 结构错误：" + e.getMessage(), e);
        }
    }

    /** 解析失败（RuntimeException：由调用方按隔离边界捕获）。 */
    public static final class XmlParseException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public XmlParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
