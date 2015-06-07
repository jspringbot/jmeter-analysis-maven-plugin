package com.lazerycode.jmeter.analyzer;

import com.lazerycode.jmeter.analyzer.parser.JMeterResultParser;
import com.lazerycode.jmeter.analyzer.parser.Parser;
import com.lazerycode.jmeter.analyzer.parser.WebDriverJMeterResultParser;

public enum ParserType {

    WEB_DRIVER {
        @Override
        public Parser createParser() {
            return new WebDriverJMeterResultParser();
        }
    },

    DEFAULT {
        @Override
        public Parser createParser() {
            return new JMeterResultParser();
        }
    };

    public abstract Parser createParser();
}
