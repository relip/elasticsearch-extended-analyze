/*
 * Copyright 2013 Jun Ohtani
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package info.johtani.elasticsearch.indices.extended.analyze;


import info.johtani.elasticsearch.action.admin.indices.extended.analyze.ExtendedAnalyzeAction;
import info.johtani.elasticsearch.action.admin.indices.extended.analyze.ExtendedAnalyzeRequest;
import info.johtani.elasticsearch.action.admin.indices.extended.analyze.ExtendedAnalyzeRequestBuilder;
import info.johtani.elasticsearch.action.admin.indices.extended.analyze.ExtendedAnalyzeResponse;
import info.johtani.elasticsearch.plugin.extended.analyze.ExtendedAnalyzePlugin;
import info.johtani.elasticsearch.rest.action.admin.indices.analyze.RestExtendedAnalyzeAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.node.Node;
import org.hamcrest.core.IsNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.elasticsearch.common.settings.Settings.*;
import static org.elasticsearch.node.NodeBuilder.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;

/**
 * TODO : currently, simple test only.
 */
public class ExtendedAnalyzeActionTests {

    private Node node;

    @Before
    public void setupServer() {
        node = nodeBuilder().settings(settingsBuilder()
                .put("path.home", "target")
                .put("path.data", "target/data")
                .put("cluster.name", "test-cluster-extended-analyze")
                .put("index.analysis.char_filter.my_mapping.type", "mapping")
                .putArray("index.analysis.char_filter.my_mapping.mappings", "PH=>F")
                .put("index.analysis.analyzer.test_analyzer.type", "custom")
                .put("index.analysis.analyzer.test_analyzer.position_increment_gap", "100")
                .put("index.analysis.analyzer.test_analyzer.tokenizer", "standard")
                .putArray("index.analysis.analyzer.test_analyzer.char_filter", "my_mapping")
                .putArray("index.analysis.analyzer.test_analyzer.filter", "snowball")
                .put("plugin.types", ExtendedAnalyzePlugin.class.getName())
        ).node();
    }

    @After
    public void closeServer() {
        node.close();
    }

    @Test
    public void analyzeUsingAnalyzerWithNoIndex() throws Exception {

        ExtendedAnalyzeResponse analyzeResponse = prepareAnalyze(node.client().admin().indices(), "THIS IS A TEST").setAnalyzer("simple").execute().actionGet();
        assertThat(analyzeResponse.tokenizer(), IsNull.nullValue());
        assertThat(analyzeResponse.tokenfilters(), IsNull.nullValue());
        assertThat(analyzeResponse.charfilters(), IsNull.nullValue());
        assertThat(analyzeResponse.analyzer().getName(), equalTo("simple"));
        assertThat(analyzeResponse.analyzer().getTokens().size(), equalTo(4));

    }

    @Test
    public void analyzeUsingCustomAnalyzerWithNoIndex() throws Exception {
        ExtendedAnalyzeResponse analyzeResponse = prepareAnalyze(node.client().admin().indices(), "THIS IS A TEST").setCharFilters("html_strip").setTokenizer("keyword").setTokenFilters("lowercase").execute().actionGet();
        assertThat(analyzeResponse.analyzer(), IsNull.nullValue());
        //charfilters
        // global charfilter is not change text.
        assertThat(analyzeResponse.charfilters().size(), equalTo(1));
        assertThat(analyzeResponse.charfilters().get(0).getName(), equalTo("html_strip"));
        assertThat(analyzeResponse.charfilters().get(0).getTexts().size(), equalTo(1));
        assertThat(analyzeResponse.charfilters().get(0).getTexts().get(0), equalTo("THIS IS A TEST"));
        //tokenizer
        assertThat(analyzeResponse.tokenizer().getName(), equalTo("keyword"));
        assertThat(analyzeResponse.tokenizer().getTokens().size(), equalTo(1));
        assertThat(analyzeResponse.tokenizer().getTokens().get(0).getTerm(), equalTo("THIS IS A TEST"));
        //tokenfilters
        assertThat(analyzeResponse.tokenfilters().size(), equalTo(1));
        assertThat(analyzeResponse.tokenfilters().get(0).getName(), equalTo("lowercase"));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().size(), equalTo(1));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(0).getTerm(), equalTo("this is a test"));


        //check other attributes
        analyzeResponse = prepareAnalyze(node.client().admin().indices(), "This is troubled").setTokenizer("standard").setTokenFilters("snowball").execute().actionGet();

        assertThat(analyzeResponse.tokenfilters().size(), equalTo(1));
        assertThat(analyzeResponse.tokenfilters().get(0).getName(), equalTo("snowball"));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().size(), equalTo(3));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(2).getTerm(), equalTo("troubl"));
        String[] expectedAttributesKey = {
            "org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute#bytes",
            "org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute#positionLength",
            "org.apache.lucene.analysis.tokenattributes.KeywordAttribute#keyword"};
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(2).getExtendedAttributes().size(), equalTo(expectedAttributesKey.length));
        Map<String, Object> extendedAttribute = null;

        for (int i = 0; i < expectedAttributesKey.length; i++) {
            String attClassName = expectedAttributesKey[i].substring(0, expectedAttributesKey[i].indexOf("#"));
            String key = expectedAttributesKey[i].substring(expectedAttributesKey[i].indexOf("#") + 1);
            extendedAttribute = analyzeResponse.tokenfilters().get(0).getTokens().get(2).getExtendedAttributes().get(attClassName);
            assertThat(extendedAttribute, notNullValue());
            assertThat(extendedAttribute.size(), equalTo(1));
            assertThat(extendedAttribute.containsKey(key), equalTo(true));
        }
    }

    @Test
    public void analyzeSpecifyAttributes() throws Exception {
        ExtendedAnalyzeResponse analyzeResponse = prepareAnalyze(node.client().admin().indices(), "This is troubled")
            .setTokenizer("standard").setTokenFilters("snowball").setAttributes("KeywordAttribute").execute().actionGet();

        assertThat(analyzeResponse.tokenfilters().size(), equalTo(1));
        assertThat(analyzeResponse.tokenfilters().get(0).getName(), equalTo("snowball"));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().size(), equalTo(3));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(2).getTerm(), equalTo("troubl"));
        String[] expectedAttributesKey = {
            "org.apache.lucene.analysis.tokenattributes.KeywordAttribute#keyword"};
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(2).getExtendedAttributes().size(), equalTo(expectedAttributesKey.length));
        Map<String, Object> extendedAttribute = null;

        for (int i = 0; i < expectedAttributesKey.length; i++) {
            String attClassName = expectedAttributesKey[i].substring(0, expectedAttributesKey[i].indexOf("#"));
            String key = expectedAttributesKey[i].substring(expectedAttributesKey[i].indexOf("#") + 1);
            extendedAttribute = analyzeResponse.tokenfilters().get(0).getTokens().get(2).getExtendedAttributes().get(attClassName);
            assertThat(extendedAttribute, notNullValue());
            assertThat(extendedAttribute.size(), equalTo(1));
            assertThat(extendedAttribute.containsKey(key), equalTo(true));
        }
    }

    private ExtendedAnalyzeRequestBuilder prepareAnalyzeNoText(IndicesAdminClient client, String index) {
        return new ExtendedAnalyzeRequestBuilder(client, ExtendedAnalyzeAction.INSTANCE, index);
    }

    private ExtendedAnalyzeRequestBuilder prepareAnalyze(IndicesAdminClient client, String text) {
        return new ExtendedAnalyzeRequestBuilder(client, ExtendedAnalyzeAction.INSTANCE, null, text);
    }

    private ExtendedAnalyzeRequestBuilder prepareAnalyze(IndicesAdminClient client, String index, String text) {
        return new ExtendedAnalyzeRequestBuilder(client, ExtendedAnalyzeAction.INSTANCE, index, text);
    }

    private Client client() {
        return node.client();
    }

    @Test
    public void simpleAnalyzerTests() throws Exception {
        try {
            client().admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }

        client().admin().indices().prepareCreate("test").execute().actionGet();
        client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForGreenStatus().execute().actionGet();

        for (int i = 0; i < 10; i++) {
            ExtendedAnalyzeResponse analyzeResponse = prepareAnalyze(client().admin().indices(), "test", "THIS IS A PHISH").setCharFilters("my_mapping").setTokenizer("keyword").setTokenFilters("lowercase").execute().actionGet();

            assertThat(analyzeResponse.analyzer(), IsNull.nullValue());
            //charfilters
            // global charfilter is not change text.
            assertThat(analyzeResponse.charfilters().size(), equalTo(1));
            assertThat(analyzeResponse.charfilters().get(0).getName(), equalTo("my_mapping"));
            assertThat(analyzeResponse.charfilters().get(0).getTexts().size(), equalTo(1));
            assertThat(analyzeResponse.charfilters().get(0).getTexts().get(0), equalTo("THIS IS A FISH"));
            //tokenizer
            assertThat(analyzeResponse.tokenizer().getName(), equalTo("keyword"));
            assertThat(analyzeResponse.tokenizer().getTokens().size(), equalTo(1));
            assertThat(analyzeResponse.tokenizer().getTokens().get(0).getTerm(), equalTo("THIS IS A FISH"));
            assertThat(analyzeResponse.tokenizer().getTokens().get(0).getStartOffset(), equalTo(0));
            assertThat(analyzeResponse.tokenizer().getTokens().get(0).getEndOffset(), equalTo(15));
            //tokenfilters
            assertThat(analyzeResponse.tokenfilters().size(), equalTo(1));
            assertThat(analyzeResponse.tokenfilters().get(0).getName(), equalTo("lowercase"));
            assertThat(analyzeResponse.tokenfilters().get(0).getTokens().size(), equalTo(1));
            assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(0).getTerm(), equalTo("this is a fish"));
            assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(0).getPosition(), equalTo(0));
            assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(0).getStartOffset(), equalTo(0));
            assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(0).getEndOffset(), equalTo(15));

        }
    }

    @Test
    public void analyzeSpecifyAttributesWithShortName() throws Exception {
        ExtendedAnalyzeResponse analyzeResponse = prepareAnalyze(node.client().admin().indices(), "This is troubled")
            .setTokenizer("standard").setTokenFilters("snowball").setAttributes("KeywordAttribute").setShortAttributeName(true).execute().actionGet();

        assertThat(analyzeResponse.tokenfilters().size(), equalTo(1));
        assertThat(analyzeResponse.tokenfilters().get(0).getName(), equalTo("snowball"));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().size(), equalTo(3));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(2).getTerm(), equalTo("troubl"));
        String[] expectedAttributesKey = {
            "KeywordAttribute#keyword"};
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().get(2).getExtendedAttributes().size(), equalTo(expectedAttributesKey.length));
        Map<String, Object> extendedAttribute = null;

        for (int i = 0; i < expectedAttributesKey.length; i++) {
            String attClassName = expectedAttributesKey[i].substring(0, expectedAttributesKey[i].indexOf("#"));
            String key = expectedAttributesKey[i].substring(expectedAttributesKey[i].indexOf("#") + 1);
            extendedAttribute = analyzeResponse.tokenfilters().get(0).getTokens().get(2).getExtendedAttributes().get(attClassName);
            assertThat(extendedAttribute, notNullValue());
            assertThat(extendedAttribute.size(), equalTo(1));
            assertThat(extendedAttribute.containsKey(key), equalTo(true));
        }
    }
    @Test
    public void testParseXContentForExtendedAnalyzeReuqest() throws Exception {
        BytesReference content =  XContentFactory.jsonBuilder()
            .startObject()
            .field("text", "THIS IS A TEST")
            .field("tokenizer", "keyword")
            .array("filters", "lowercase")
            .endObject().bytes();

        ExtendedAnalyzeRequest analyzeRequest = new ExtendedAnalyzeRequest("for test");

        RestExtendedAnalyzeAction.buildFromContent(content, analyzeRequest);

        assertThat(analyzeRequest.text()[0], equalTo("THIS IS A TEST"));
        assertThat(analyzeRequest.tokenizer(), equalTo("keyword"));
        assertThat(analyzeRequest.tokenFilters(), equalTo(new String[]{"lowercase"}));
    }

    @Test
    public void testParseXContentForExtendedAnalyzeRequestWithInvalidJsonThrowsException() throws Exception {
        ExtendedAnalyzeRequest analyzeRequest = new ExtendedAnalyzeRequest("for test");
        BytesReference invalidContent =  XContentFactory.jsonBuilder().startObject().value("invalid_json").endObject().bytes();

        try {
            RestExtendedAnalyzeAction.buildFromContent(invalidContent, analyzeRequest);
            fail("shouldn't get here");
        } catch (Exception e) {
            assertThat(e, instanceOf(IllegalArgumentException.class));
            assertThat(e.getMessage(), equalTo("Failed to parse request body"));
        }
    }



    @Test
    public void testParseXContentForExtendedAnalyzeRequestWithUnknownParamThrowsException() throws Exception {
        ExtendedAnalyzeRequest analyzeRequest = new ExtendedAnalyzeRequest("for test");
        BytesReference invalidContent =XContentFactory.jsonBuilder()
            .startObject()
            .field("text", "THIS IS A TEST")
            .field("unknown", "keyword")
            .endObject().bytes();

        try {
            RestExtendedAnalyzeAction.buildFromContent(invalidContent, analyzeRequest);
            fail("shouldn't get here");
        } catch (Exception e) {
            assertThat(e, instanceOf(IllegalArgumentException.class));
            assertThat(e.getMessage(), startsWith("Unknown parameter [unknown]"));
        }
    }


    @Test
    public void analyzeWithMultiValues() throws Exception {

        try {
            client().admin().indices().prepareDelete("test2").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }

        //only analyzer =
        client().admin().indices().prepareCreate("test2").get();
        client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForGreenStatus().execute().actionGet();

        client().admin().indices().preparePutMapping("test2")
            .setType("document").setSource("simple", "type=string,analyzer=simple,position_increment_gap=100").get();


        String[] texts = new String[]{"THIS IS A TEST", "THE SECOND TEXT"};
        ExtendedAnalyzeResponse analyzeResponse = prepareAnalyzeNoText(node.client().admin().indices(), "test2")
            .setField("simple").setShortAttributeName(true).setText(texts).execute().get();


        assertThat(analyzeResponse.analyzer().getName(), equalTo("simple"));
        assertThat(analyzeResponse.analyzer().getTokens().size(), equalTo(7));
        ExtendedAnalyzeResponse.ExtendedAnalyzeToken token = analyzeResponse.analyzer().getTokens().get(3);

        assertThat(token.getTerm(), equalTo("test"));
        assertThat(token.getPosition(), equalTo(3));
        assertThat(token.getStartOffset(), equalTo(10));
        assertThat(token.getEndOffset(), equalTo(14));

        token = analyzeResponse.analyzer().getTokens().get(5);
        assertThat(token.getTerm(), equalTo("second"));
        assertThat(token.getPosition(), equalTo(105));
        assertThat(token.getStartOffset(), equalTo(19));
        assertThat(token.getEndOffset(), equalTo(25));

    }


    @Test
    public void analyzeWithMultiValuesWithCustomAnalyzer() throws Exception {

        try {
            client().admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }

        client().admin().indices().prepareCreate("test").execute().actionGet();
        client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForGreenStatus().execute().actionGet();

        //only analyzer =
        String[] texts = new String[]{"this is a PHISH", "the troubled text"};
        ExtendedAnalyzeResponse analyzeResponse = prepareAnalyzeNoText(node.client().admin().indices(), "test")
            .setAnalyzer("test_analyzer").setShortAttributeName(true).setText(texts).execute().get();

        // charfilter
        assertThat(analyzeResponse.charfilters().size(), equalTo(1));
        assertThat(analyzeResponse.charfilters().get(0).getName(), equalTo("my_mapping"));
        assertThat(analyzeResponse.charfilters().get(0).getTexts().size(), equalTo(2));
        assertThat(analyzeResponse.charfilters().get(0).getTexts().get(0), equalTo("this is a FISH"));
        assertThat(analyzeResponse.charfilters().get(0).getTexts().get(1), equalTo("the troubled text"));

        // tokenizer
        assertThat(analyzeResponse.tokenizer().getName(), equalTo("standard"));
        assertThat(analyzeResponse.tokenizer().getTokens().size(), equalTo(7));
        ExtendedAnalyzeResponse.ExtendedAnalyzeToken token = analyzeResponse.tokenizer().getTokens().get(3);

        assertThat(token.getTerm(), equalTo("FISH"));
        assertThat(token.getPosition(), equalTo(3));
        assertThat(token.getStartOffset(), equalTo(10));
        assertThat(token.getEndOffset(), equalTo(15));

        token = analyzeResponse.tokenizer().getTokens().get(5);
        assertThat(token.getTerm(), equalTo("troubled"));
        assertThat(token.getPosition(), equalTo(105));
        assertThat(token.getStartOffset(), equalTo(20));
        assertThat(token.getEndOffset(), equalTo(28));

        // tokenfilter

        assertThat(analyzeResponse.tokenfilters().size(), equalTo(1));
        assertThat(analyzeResponse.tokenfilters().get(0).getName(), equalTo("snowball"));
        assertThat(analyzeResponse.tokenfilters().get(0).getTokens().size(), equalTo(7));
        token = analyzeResponse.tokenfilters().get(0).getTokens().get(3);

        assertThat(token.getTerm(), equalTo("FISH"));
        assertThat(token.getPosition(), equalTo(3));
        assertThat(token.getStartOffset(), equalTo(10));
        assertThat(token.getEndOffset(), equalTo(15));

        token = analyzeResponse.tokenfilters().get(0).getTokens().get(5);
        assertThat(token.getTerm(), equalTo("troubl"));
        assertThat(token.getPosition(), equalTo(105));
        assertThat(token.getStartOffset(), equalTo(20));
        assertThat(token.getEndOffset(), equalTo(28));


    }


}
