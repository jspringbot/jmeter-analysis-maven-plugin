package com.lazerycode.jmeter.analyzer.parser;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;

public interface Parser {
    Map<String, AggregatedResponses> aggregate(Reader reader) throws IOException, SAXException;
}
