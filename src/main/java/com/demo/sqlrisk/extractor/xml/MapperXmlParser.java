package com.demo.sqlrisk.extractor.xml;

import com.demo.sqlrisk.util.XmlSupport;
import org.xml.sax.helpers.DefaultHandler;

/**
 * mapper.xml 解析入口（详细设计 §9.2）：JDK SAX + Locator（决策 D3），
 * XXE 加固配置见 {@link XmlSupport}；Locator 行号语义（endElement = 闭合标签行）已由 M0 spike 验证。
 */
final class MapperXmlParser {

    private MapperXmlParser() {
    }

    /** 廉价的 mapper 预检，避免解析 pom.xml / logback.xml 等无关 XML。 */
    static boolean looksLikeMapper(String content) {
        return content != null && content.contains("<mapper");
    }

    /**
     * 解析内容；XML 结构错误包装为 {@link MapperXmlParseException}，
     * 由调用方按文件隔离（该文件数据丢弃 + 记入 parseFailedFiles）。
     */
    static void parse(String content, DefaultHandler handler) {
        try {
            XmlSupport.parse(content, handler);
        } catch (XmlSupport.XmlParseException e) {
            throw new MapperXmlParseException(e.getMessage(), e);
        }
    }

    /** 单文件解析失败（按文件隔离为 SKIP，不中断整体）。 */
    static final class MapperXmlParseException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MapperXmlParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
