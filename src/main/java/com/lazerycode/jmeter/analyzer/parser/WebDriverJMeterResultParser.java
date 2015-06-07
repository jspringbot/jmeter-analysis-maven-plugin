package com.lazerycode.jmeter.analyzer.parser;

import com.lazerycode.jmeter.analyzer.RequestGroup;
import com.lazerycode.jmeter.analyzer.statistics.Samples;
import org.apache.maven.plugin.logging.Log;
import org.springframework.util.AntPathMatcher;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

import static com.lazerycode.jmeter.analyzer.config.Environment.ENVIRONMENT;
import static com.lazerycode.jmeter.analyzer.parser.StatusCodes.HTTPCODE_CONNECTIONERROR;
import static com.lazerycode.jmeter.analyzer.parser.StatusCodes.HTTPCODE_ERROR;

public class WebDriverJMeterResultParser implements Parser {

    /**
     * number of parsed items after which a log message is written
     */
    private static final int LOGMESSAGE_ITEMS = 10000;

    public Map<String, AggregatedResponses> aggregate(Reader reader) throws IOException, SAXException {

        SAXParser saxParser;
        try {

            saxParser = SAXParserFactory.newInstance().newSAXParser();
        } catch (ParserConfigurationException e) {

            throw new IllegalStateException("Parser could not be created ", e);
        }

        Parser parser = new Parser();
        saxParser.parse(new InputSource(reader), parser);

        return parser.getResults();
    }


    // ==================

    /**
     * @return the current log
     */
    private static Log getLog() {
        return ENVIRONMENT.getLog();
    }

    /**
     * Parser does the heavy lifting.
     */
    private static class Parser extends DefaultHandler {

        private final AntPathMatcher matcher = new AntPathMatcher();

        private final int maxSamples;
        private final List<RequestGroup> pathPatterns;
        private final boolean sizeByUris;
        private final boolean durationByUris;

        private long parsedCount = 0;

        private Map<String, AggregatedResponses> results = new LinkedHashMap<String, AggregatedResponses>();
        private Set<String> nodeNames;

        private StringBuilder buf = new StringBuilder();

        private String uri;

        private long timestamp;

        private boolean success;

        private String key;

        private long bytes = 0;

        // --- parse duration
        private long duration;

        // --- parse active thread for all groups
        private long activeThreads;

        // --- parse responseCode
        private int responseCode;

        /**
         * Constructor.
         * Fields configured from Environment
         */
        public Parser() {
            this(ENVIRONMENT.getMaxSamples(),
                    ENVIRONMENT.getRequestGroups(),
                    ENVIRONMENT.isGenerateDetails(),
                    ENVIRONMENT.isGenerateDetails(),
                    ENVIRONMENT.getSampleNames());
        }

        /**
         * Constructor.
         *
         * @param maxSamples     The maximum number of samples that be stored internally for every metric
         * @param pathPatterns   A number of ANT patterns. If set then the resulting {@link com.lazerycode.jmeter.analyzer.parser.AggregatedResponses} will be
         *                       grouped by uris matching these patterns. If not set then the threadgroup is used
         * @param sizeByUris     true, if the response size shall be counted for each uri separately
         * @param durationByUris true, if the response duration shall be counted for each uri separately
         * @param nodeNames      Set of node names to process
         */
        public Parser(int maxSamples, List<RequestGroup> pathPatterns, boolean sizeByUris, boolean durationByUris, Set<String> nodeNames) {
            this.maxSamples = maxSamples;
            this.pathPatterns = pathPatterns;
            this.sizeByUris = sizeByUris;
            this.durationByUris = durationByUris;
            this.nodeNames = nodeNames;
        }

        /**
         * @return a mapping from identifier to aggregatedResult
         */
        public Map<String, AggregatedResponses> getResults() {
            return results;
        }

        @Override
        public void startElement(String u, String localName, String qName, Attributes attributes) throws SAXException {
            buf.delete(0, buf.length());
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            buf.append(new String(ch, start, length));
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if("location".equals(qName)) {
                this.uri = buf.toString();
            } else if("timeStamp".equals(qName)) {
                timestamp = Long.valueOf(buf.toString());
            } else if("success".equals(qName)) {
                success = Boolean.valueOf(buf.toString());
            } else if("label".equals(qName)) {
                key = buf.toString();
            } else if("elapsedTime".equals(qName)) {
                duration = Long.valueOf(buf.toString());
            } else if("allThreads".equals(qName)) {
                activeThreads = Long.valueOf(buf.toString());
            } else if("responseCode".equals(qName)) {
                responseCode = Integer.valueOf(buf.toString());
            }

            if (nodeNames.contains(localName) || nodeNames.contains(qName)) {
                // --- create / provide result container
                AggregatedResponses resultContainer = getResult(key);

                addData(resultContainer, this.uri, timestamp, bytes, duration, activeThreads, responseCode, success);

                parsedCount++;

                // write a log message every 10000 entries
                if (parsedCount % LOGMESSAGE_ITEMS == 0) {
                    getLog().info("Parsed " + parsedCount + " entries ...");
                }
            }
        }

        @Override
        public void endDocument() throws SAXException {
            super.endDocument();
            //finish collection of responses/samples
            for (AggregatedResponses responses : results.values()) {
                responses.finish();
            }

            getLog().info("Finished Parsing " + parsedCount + " entries.");
        }

        //==================================================================================================================

        /**
         * Add data from httpSample to {@link com.lazerycode.jmeter.analyzer.parser.AggregatedResponses the resultContainer}
         *
         * @param resultContainer container to add data to
         * @param uri             uri identifying the resultContainer
         * @param timestamp       httpSample timestamp
         * @param bytes           httpSample bytes
         * @param duration        httpSample duration
         * @param responseCode    httpSample responseCode
         * @param success         httpSample success
         */
        private void addData(AggregatedResponses resultContainer, String uri,
                             long timestamp, long bytes, long duration, long activeThreads, int responseCode, boolean success) {


            StatusCodes statusCodes = resultContainer.getStatusCodes();
            statusCodes.increment(responseCode);

            Map<Integer, Set<String>> uriByStatusCodeMapping = resultContainer.getUriByStatusCode();
            add(uriByStatusCodeMapping, responseCode, uri);

            Samples activeThreadResult = resultContainer.getActiveThreads();
            activeThreadResult.addSample(timestamp + duration, activeThreads);

            // -- register data
            if (!success || bytes == -1 || duration == -1 ||
                    responseCode >= HTTPCODE_ERROR || responseCode == HTTPCODE_CONNECTIONERROR) {

                // httpSample is not okay
                // 4xx (client error) or 5xx (server error)
                Samples requestResult = resultContainer.getDuration();
                requestResult.addError(timestamp);
                Samples bytesResult = resultContainer.getSize();
                bytesResult.addError(timestamp);
            } else {

                // httpSample is okay
                Samples bytesResult = resultContainer.getSize();
                bytesResult.addSample(timestamp, bytes);
                Samples requestResult = resultContainer.getDuration();
                requestResult.addSample(timestamp, duration);

                Map<String, Samples> sizeByUriMapping = resultContainer.getSizeByUri();
                Map<String, Samples> durationByUriMapping = resultContainer.getDurationByUri();

                add(sizeByUriMapping, uri, timestamp, bytes);
                add(durationByUriMapping, uri, timestamp, duration);
            }

            //set start and end time
            if (resultContainer.getStart() == 0) {
                resultContainer.setStart(timestamp);
            }
            resultContainer.setEnd(timestamp);

        }

        /**
         * Create / provide {@link com.lazerycode.jmeter.analyzer.parser.AggregatedResponses result container}
         *
         * @param key identifier
         * @return the aggregated response matching the key
         */
        private AggregatedResponses getResult(String key) {

            AggregatedResponses resultContainer = results.get(key);
            if (resultContainer == null) {

                //initialize new AggregatedResponses
                resultContainer = new AggregatedResponses();
                resultContainer.setActiveThreads(new Samples(maxSamples, true));
                resultContainer.setDuration(new Samples(maxSamples, true));
                resultContainer.setSize(new Samples(maxSamples, false));
                resultContainer.setStatusCodes(new StatusCodes());
                resultContainer.setUriByStatusCode(new HashMap<Integer, Set<String>>());
                if (sizeByUris) {
                    resultContainer.setSizeByUri(new HashMap<String, Samples>());
                }
                if (durationByUris) {
                    resultContainer.setDurationByUri(new HashMap<String, Samples>());
                }

                results.put(key, resultContainer);
            }

            return resultContainer;
        }

        /**
         * Get the reponse code from the {@link org.xml.sax.Attributes}
         * Response code in <httpSample> element may not be an Integer, this is a safeguard against that.
         *
         * @param atts attributes to extract the response code from
         * @return a valid response code
         */
        private int getResponseCode(Attributes atts) {

            int responseCode;
            String responseCodeString = atts.getValue("rc");
            try {

                responseCode = Integer.valueOf(responseCodeString);
            } catch (Exception e) {
                getLog().warn("Error parsing response code '" + responseCodeString + "'");
                responseCode = HTTPCODE_CONNECTIONERROR;
            }

            return responseCode;
        }

        /**
         * Add #timestamp and matching #value to (new) Samples object matching the given #uri to given Map #uriSamples
         *
         * @param uriSamples map to add the Samples to
         * @param uri        the uri identifying the Samples object
         * @param timestamp  the timestamp
         * @param value      the value
         */
        private void add(Map<String, Samples> uriSamples, String uri, long timestamp, long value) {

            if (uriSamples != null) {

                Samples samples = uriSamples.get(uri);

                if (samples == null) {
                    // no Sample was previously stored for the uri.
                    samples = new Samples(0, false); // 0 = don't collect samples. This is important, otherwise a OOM may occur if the result set is big

                    uriSamples.put(uri, samples);
                }

                samples.addSample(timestamp, value);
            }
        }

        private void add(Map<Integer, Set<String>> uriByStatusCode, Integer code, String uri) {
            if (uriByStatusCode != null) {

                Set<String> uriSet = uriByStatusCode.get(code);

                if (uriSet == null) {
                    uriSet = new HashSet<String>();
                    uriByStatusCode.put(code, uriSet);
                }

                uriSet.add(uri);
            }
        }
    }

}
