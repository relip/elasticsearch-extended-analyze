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

package info.johtani.elasticsearch.action.admin.indices.extended.analyze;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeReflector;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.single.shard.TransportSingleShardAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.ShardsIterator;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.analysis.*;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.internal.AllFieldMapper;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

/**
 * Based on elasticsearch TransportAnalyzeAction
 */
public class TransportExtendedAnalyzeAction extends TransportSingleShardAction<ExtendedAnalyzeRequest, ExtendedAnalyzeResponse> {

    private final IndicesService indicesService;
    private final IndicesAnalysisService indicesAnalysisService;

    private static final Settings DEFAULT_SETTINGS = Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT).build();

    @Inject
    public TransportExtendedAnalyzeAction(Settings settings, ThreadPool threadPool, ClusterService clusterService, TransportService transportService,
                                          IndicesService indicesService, IndicesAnalysisService indicesAnalysisService, ActionFilters actionFilters,
                                          IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, ExtendedAnalyzeAction.NAME, threadPool, clusterService, transportService, actionFilters, indexNameExpressionResolver,
            ExtendedAnalyzeRequest.class, ThreadPool.Names.INDEX);
        this.indicesService = indicesService;
        this.indicesAnalysisService = indicesAnalysisService;
    }

    @Override
    protected ExtendedAnalyzeResponse newResponse() {
        return new ExtendedAnalyzeResponse();
    }

    @Override
    protected ClusterBlockException checkRequestBlock(ClusterState state, InternalRequest request) {
        if (request.concreteIndex() != null) {
            return super.checkRequestBlock(state, request);
        }
        return null;
    }

    @Override
    protected ShardsIterator shards(ClusterState state, InternalRequest request) {
        if (request.concreteIndex() == null) {
            // just execute locally....
            return null;
        }
        return state.routingTable().index(request.concreteIndex()).randomAllActiveShardsIt();
    }

    @Override
    protected ExtendedAnalyzeResponse shardOperation(ExtendedAnalyzeRequest request, ShardId shardId) throws ElasticsearchException {
        IndexService indexService = null;
        if (shardId != null) {
            indexService = indicesService.indexServiceSafe(shardId.getIndex());
        }
        Analyzer analyzer = null;
        boolean closeAnalyzer = false;
        String field = null;
        if (request.field() != null) {
            if (indexService == null) {
                throw new IllegalArgumentException("No index provided, and trying to analyzer based on a specific field which requires the index parameter");
            }
            MappedFieldType fieldMapper = indexService.mapperService().smartNameFieldType(request.field());
            if (fieldMapper != null) {
                if (fieldMapper.isNumeric()) {
                    throw new IllegalArgumentException("Can't process field [" + request.field() + "], Analysis requests are not supported on numeric fields");
                }
                analyzer = fieldMapper.indexAnalyzer();
                field = fieldMapper.names().indexName();

            }
        }
        if (field == null) {
            if (indexService != null) {
                field = indexService.queryParserService().defaultField();
            } else {
                field = AllFieldMapper.NAME;
            }
        }
        if (analyzer == null && request.analyzer() != null) {
            if (indexService == null) {
                analyzer = indicesAnalysisService.analyzer(request.analyzer());
            } else {
                analyzer = indexService.analysisService().analyzer(request.analyzer());
            }
            if (analyzer == null) {
                throw new IllegalArgumentException("failed to find analyzer [" + request.analyzer() + "]");
            }
        } else if (request.tokenizer() != null) {
            TokenizerFactory tokenizerFactory;
            if (indexService == null) {
                TokenizerFactoryFactory tokenizerFactoryFactory = indicesAnalysisService.tokenizerFactoryFactory(request.tokenizer());
                if (tokenizerFactoryFactory == null) {
                    throw new IllegalArgumentException("failed to find global tokenizer under [" + request.tokenizer() + "]");
                }
                tokenizerFactory = tokenizerFactoryFactory.create(request.tokenizer(), DEFAULT_SETTINGS);
            } else {
                tokenizerFactory = indexService.analysisService().tokenizer(request.tokenizer());
                if (tokenizerFactory == null) {
                    throw new IllegalArgumentException("failed to find tokenizer under [" + request.tokenizer() + "]");
                }
            }
            TokenFilterFactory[] tokenFilterFactories = new TokenFilterFactory[0];
            if (request.tokenFilters() != null && request.tokenFilters().length > 0) {
                tokenFilterFactories = new TokenFilterFactory[request.tokenFilters().length];
                for (int i = 0; i < request.tokenFilters().length; i++) {
                    String tokenFilterName = request.tokenFilters()[i];
                    if (indexService == null) {
                        TokenFilterFactoryFactory tokenFilterFactoryFactory = indicesAnalysisService.tokenFilterFactoryFactory(tokenFilterName);
                        if (tokenFilterFactoryFactory == null) {
                            throw new IllegalArgumentException("failed to find global token filter under [" + request.tokenizer() + "]");
                        }
                        tokenFilterFactories[i] = tokenFilterFactoryFactory.create(tokenFilterName, DEFAULT_SETTINGS);
                    } else {
                        tokenFilterFactories[i] = indexService.analysisService().tokenFilter(tokenFilterName);
                        if (tokenFilterFactories[i] == null) {
                            throw new IllegalArgumentException("failed to find token filter under [" + request.tokenizer() + "]");
                        }
                    }
                    if (tokenFilterFactories[i] == null) {
                        throw new IllegalArgumentException("failed to find token filter under [" + request.tokenizer() + "]");
                    }
                }
            }
            CharFilterFactory[] charFilterFactories = new CharFilterFactory[0];
            if (request.charFilters() != null && request.charFilters().length > 0) {
                charFilterFactories = new CharFilterFactory[request.charFilters().length];
                for (int i = 0; i < request.charFilters().length; i++) {
                    String charFilterName = request.charFilters()[i];
                    if (indexService == null) {
                        CharFilterFactoryFactory charFilterFactoryFactory = indicesAnalysisService.charFilterFactoryFactory(charFilterName);
                        if (charFilterFactoryFactory == null) {
                            throw new IllegalArgumentException("failed to find global char filter top [" + request.tokenizer() + "]");
                        }
                        charFilterFactories[i] = charFilterFactoryFactory.create(charFilterName, DEFAULT_SETTINGS);
                    } else {
                        charFilterFactories[i] = indexService.analysisService().charFilter(charFilterName);
                        if (charFilterFactories[i] == null) {
                            throw new IllegalArgumentException("failed to find char filter top [" + request.tokenizer() + "]");
                        }
                    }
                    if (charFilterFactories[i] == null) {
                        throw new IllegalArgumentException("failed to find char filter top [" + request.tokenizer() + "]");
                    }
                }
            }
            analyzer = new CustomAnalyzer(tokenizerFactory, charFilterFactories, tokenFilterFactories);
            closeAnalyzer = true;
        } else if (analyzer == null) {
            if (indexService == null) {
                analyzer = Lucene.STANDARD_ANALYZER;
            } else {
                analyzer = indexService.analysisService().defaultIndexAnalyzer();
            }
        }
        if (analyzer == null) {
            throw new IllegalArgumentException("failed to find analyzer");
        }

        return buildResponse(request, analyzer, closeAnalyzer, field);
    }

    private ExtendedAnalyzeResponse buildResponse(ExtendedAnalyzeRequest request, Analyzer analyzer, boolean closeAnalyzer, String field) {
        ExtendedAnalyzeResponse response = new ExtendedAnalyzeResponse();
        final Set<String> includeAttributes = new HashSet<>();
        if (request.attributes() != null && request.attributes().length > 0) {
            for (String attribute : request.attributes()) {
                includeAttributes.add(attribute.toLowerCase());
            }
        }

        CustomAnalyzer customAnalyzer = null;
        if (analyzer instanceof CustomAnalyzer) {
            customAnalyzer = (CustomAnalyzer)analyzer;
        } else if (analyzer instanceof NamedAnalyzer && ((NamedAnalyzer) analyzer).analyzer() instanceof CustomAnalyzer) {
            customAnalyzer = (CustomAnalyzer) ((NamedAnalyzer) analyzer).analyzer();
        }
        if (customAnalyzer != null) {

            // customAnalyzer = divide charfilter, tokenizer tokenfilters
            CharFilterFactory[] charfilterFactories = customAnalyzer.charFilters();
            TokenizerFactory tokenizerFactory = customAnalyzer.tokenizerFactory();
            TokenFilterFactory[] tokenfilterFactories = customAnalyzer.tokenFilters();

            Map<String, List<String>> charFiltersTexts = new HashMap<>(charfilterFactories.length);
            Map<String, TokenListCreator> tokenFiltersTokenListCreator = new HashMap<>(tokenfilterFactories.length);

            TokenListCreator tokenizerTokenListCreator = new TokenListCreator();

            for (String text : request.text()) {
                String charFilteredSource = text;

                Reader reader = new StringReader(text);
                if (charfilterFactories != null) {
                    for (CharFilterFactory charfilter : charfilterFactories) {
                        reader = charfilter.create(reader);
                        Reader readerForWriteOut = new StringReader(charFilteredSource);
                        readerForWriteOut = charfilter.create(readerForWriteOut);
                        charFilteredSource = writeCharStream(readerForWriteOut);

                        List<String> texts = charFiltersTexts.get(charfilter.name());
                        if (texts == null) {
                            texts = new ArrayList<>();
                        }
                        texts.add(charFilteredSource);
                        charFiltersTexts.put(charfilter.name(), texts);
                    }
                }

                //analyzing only tokenizer
                try {
                    Tokenizer tokenizer = tokenizerFactory.create();
                    tokenizer.setReader(reader);
                    TokenStream stream = (TokenStream)tokenizer;
                    tokenizerTokenListCreator.analyze(stream, customAnalyzer, field, includeAttributes, request.shortAttributeName());

                    //analyzing each tokenfilter
                    if (tokenfilterFactories != null) {
                        for (int i = 0; i < tokenfilterFactories.length; i++) {
                            TokenListCreator tokenfilterTokenListCreator = tokenFiltersTokenListCreator.get(tokenfilterFactories[i].name());
                            if (tokenfilterTokenListCreator == null) {
                                tokenfilterTokenListCreator = new TokenListCreator();
                                tokenFiltersTokenListCreator.put(tokenfilterFactories[i].name(), tokenfilterTokenListCreator);
                            }
                            stream = createStackedTokenStream(text, charfilterFactories, tokenizerFactory, tokenfilterFactories, i + 1);
                            tokenfilterTokenListCreator.analyze(stream, customAnalyzer, field, includeAttributes, request.shortAttributeName());
                        }
                    }
                } catch (IOException ioe) {
                    throw new ElasticsearchException("failed to analyze", ioe);
                }
            }

            for (String charFilterName : charFiltersTexts.keySet()) {
                response.addCharfilter(new ExtendedAnalyzeResponse.CharFilteredText(charFilterName, charFiltersTexts.get(charFilterName)));
            }
            response.customAnalyzer(true).tokenizer(new ExtendedAnalyzeResponse.ExtendedAnalyzeTokenList(tokenizerFactory.name(), tokenizerTokenListCreator.getTokens()));
            for (String tokenFilterName : tokenFiltersTokenListCreator.keySet()) {
                response.customAnalyzer(true).addTokenfilter(
                    new ExtendedAnalyzeResponse.ExtendedAnalyzeTokenList(tokenFilterName, tokenFiltersTokenListCreator.get(tokenFilterName).getTokens()));
            }

        } else {
            // no extended
            analyzeSimple(request, analyzer, field, response, includeAttributes);
        }

        if (closeAnalyzer) {
            analyzer.close();
        }

        return response;
    }

    private void analyzeSimple(ExtendedAnalyzeRequest request, Analyzer analyzer, String field,
                                      ExtendedAnalyzeResponse response, Set<String> includeAttributes){
        String name;
        if (analyzer instanceof NamedAnalyzer) {
            name = ((NamedAnalyzer) analyzer).name();
        } else {
            name = analyzer.getClass().getName();
        }

        TokenListCreator tokenListCreator = new TokenListCreator();
        for (String text : request.text()){
            try {
                tokenListCreator.analyze(analyzer.tokenStream(field, text), analyzer, field, includeAttributes, request.shortAttributeName());
            } catch (IOException e) {
                throw new ElasticsearchException("failed to analyze", e);
            }
        }
        response.customAnalyzer(false).analyzer(new ExtendedAnalyzeResponse.ExtendedAnalyzeTokenList(name, tokenListCreator.getTokens()));
    }


    private TokenStream createStackedTokenStream(String source, CharFilterFactory[] charfilterFactories, TokenizerFactory tokenizerFactory, TokenFilterFactory[] tokenfilterFactories, int current) throws IOException{
        Reader reader = new StringReader(source);
        for (CharFilterFactory charfilter : charfilterFactories) {
            reader = charfilter.create(reader);
        }
        Tokenizer tokenizer = tokenizerFactory.create();
        tokenizer.setReader(reader);
        TokenStream tokenStream = (TokenStream)tokenizer;
        for (int i = 0; i < current; i++) {
            tokenStream = tokenfilterFactories[i].create(tokenStream);
        }

        return tokenStream;
    }

    private String writeCharStream(Reader input) {
        final int BUFFER_SIZE = 1024;
        char[] buf = new char[BUFFER_SIZE];
        int len;
        StringBuilder sb = new StringBuilder();
        do {
            try {
                len = input.read(buf, 0, BUFFER_SIZE);
            } catch (IOException e) {
                throw new ElasticsearchException("failed to analyze (charfiltering)", e);
            }
            if (len > 0)
                sb.append(buf, 0, len);
        } while (len == BUFFER_SIZE);
        return sb.toString();
    }

    private List<ExtendedAnalyzeResponse.ExtendedAnalyzeToken> processAnalysis(TokenStream stream, Set<String> includeAttributes, boolean shortAttrName, int lastPosition, int lastOffset) throws IOException {
        List<ExtendedAnalyzeResponse.ExtendedAnalyzeToken> tokens = new ArrayList<>();
        stream.reset();

        //and each tokens output
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        PositionIncrementAttribute posIncr = stream.addAttribute(PositionIncrementAttribute.class);
        OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
        TypeAttribute type = stream.addAttribute(TypeAttribute.class);

        while (stream.incrementToken()) {
            int increment = posIncr.getPositionIncrement();
            if (increment > 0) {
                lastPosition = lastPosition + increment;
            }

            tokens.add(new ExtendedAnalyzeResponse.ExtendedAnalyzeToken(term.toString(), lastPosition, lastOffset + offset.startOffset(),
                lastOffset +offset.endOffset(), type.type(), extractExtendedAttributes(stream, includeAttributes, shortAttrName)));
        }
        stream.end();
        return tokens;

    }


    private class TokenListCreator {
        int lastPosition = -1;
        int lastOffset = 0;
        List<ExtendedAnalyzeResponse.ExtendedAnalyzeToken> tokens;

        TokenListCreator() {
            tokens = new ArrayList<>();
        }

        private void analyze(TokenStream stream, Analyzer analyzer, String field, Set<String> includeAttributes, boolean shortAttrName) {
            try {
                stream.reset();
                CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
                PositionIncrementAttribute posIncr = stream.addAttribute(PositionIncrementAttribute.class);
                OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
                TypeAttribute type = stream.addAttribute(TypeAttribute.class);

                while (stream.incrementToken()) {
                    int increment = posIncr.getPositionIncrement();
                    if (increment > 0) {
                        lastPosition = lastPosition + increment;
                    }
                    tokens.add(new ExtendedAnalyzeResponse.ExtendedAnalyzeToken(term.toString(), lastPosition, lastOffset + offset.startOffset(),
                        lastOffset +offset.endOffset(), type.type(), extractExtendedAttributes(stream, includeAttributes, shortAttrName)));

                }
                stream.end();
                lastOffset += offset.endOffset();
                lastPosition += posIncr.getPositionIncrement();

                lastPosition += analyzer.getPositionIncrementGap(field);
                lastOffset += analyzer.getOffsetGap(field);

            } catch (IOException e) {
                throw new ElasticsearchException("failed to analyze", e);
            } finally {
                IOUtils.closeWhileHandlingException(stream);
            }
        }

        private List<ExtendedAnalyzeResponse.ExtendedAnalyzeToken> getTokens(){
            return tokens;
        }

    }

    /**
     * other attribute extract object.<br/>
     * Extracted object group by AttributeClassName
     *
     * @param stream current TokenStream
     * @param includeAttributes filtering attributes
     * @param shortAttrName if true, return short attribute name
     * @return Nested Object : Map<attrClass, Map<key, value>>
     */
    private Map<String, Map<String, Object>> extractExtendedAttributes(TokenStream stream, final Set<String> includeAttributes, final boolean shortAttrName) {
        final Map<String, Map<String, Object>> extendedAttributes = new TreeMap<>();

        stream.reflectWith(new AttributeReflector() {
            @Override
            public void reflect(Class<? extends Attribute> attClass, String key, Object value) {
                if (CharTermAttribute.class.isAssignableFrom(attClass))
                    return;
                if (PositionIncrementAttribute.class.isAssignableFrom(attClass))
                    return;
                if (OffsetAttribute.class.isAssignableFrom(attClass))
                    return;
                if (TypeAttribute.class.isAssignableFrom(attClass))
                    return;
                if (includeAttributes == null || includeAttributes.isEmpty() || includeAttributes.contains(attClass.getSimpleName().toLowerCase())) {
                    Map<String, Object> currentAttributes = extendedAttributes.get(attClass.getName());
                    if (currentAttributes == null) {
                        currentAttributes = new HashMap<>();
                    }

                    if (value instanceof BytesRef) {
                        final BytesRef p = (BytesRef) value;
                        value = p.toString();
                    }
                    currentAttributes.put(key, value);
                    if (shortAttrName) {
                        extendedAttributes.put(attClass.getName().substring(attClass.getName().lastIndexOf(".")+1), currentAttributes);
                    } else {
                        extendedAttributes.put(attClass.getName(), currentAttributes);
                    }
                }
            }
        });

        return extendedAttributes;
    }

    @Override
    protected boolean resolveIndex(ExtendedAnalyzeRequest request) {
        return request.index() != null;
    }
}

