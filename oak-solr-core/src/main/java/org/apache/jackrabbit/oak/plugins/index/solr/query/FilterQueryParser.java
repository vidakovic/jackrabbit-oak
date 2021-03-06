/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.solr.query;

import java.util.Collection;

import org.apache.jackrabbit.oak.plugins.index.solr.configuration.OakSolrConfiguration;
import org.apache.jackrabbit.oak.query.fulltext.FullTextAnd;
import org.apache.jackrabbit.oak.query.fulltext.FullTextContains;
import org.apache.jackrabbit.oak.query.fulltext.FullTextExpression;
import org.apache.jackrabbit.oak.query.fulltext.FullTextOr;
import org.apache.jackrabbit.oak.query.fulltext.FullTextTerm;
import org.apache.jackrabbit.oak.query.fulltext.FullTextVisitor;
import org.apache.jackrabbit.oak.spi.query.Filter;
import org.apache.solr.client.solrj.SolrQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.jackrabbit.oak.commons.PathUtils.getName;

/**
 * the {@link org.apache.jackrabbit.oak.plugins.index.solr.query.FilterQueryParser} can parse {@link org.apache.jackrabbit.oak.spi.query.Filter}s
 * and transform them into {@link org.apache.solr.client.solrj.SolrQuery}s and / or Solr query {@code String}s.
 */
class FilterQueryParser {

    private static final Logger log = LoggerFactory.getLogger(FilterQueryParser.class);

    static SolrQuery getQuery(Filter filter, OakSolrConfiguration configuration) {

        SolrQuery solrQuery = new SolrQuery();
        setDefaults(solrQuery, configuration);

        StringBuilder queryBuilder = new StringBuilder();

        FullTextExpression ft = filter.getFullTextConstraint();
        if (ft != null) {
            queryBuilder.append(parseFullTextExpression(ft, configuration));
            queryBuilder.append(' ');
        } else if (filter.getFulltextConditions() != null) {
            Collection<String> fulltextConditions = filter.getFulltextConditions();
            for (String fulltextCondition : fulltextConditions) {
                queryBuilder.append(fulltextCondition).append(" ");
            }
        }

        Collection<Filter.PropertyRestriction> propertyRestrictions = filter.getPropertyRestrictions();
        if (propertyRestrictions != null && !propertyRestrictions.isEmpty()) {
            for (Filter.PropertyRestriction pr : propertyRestrictions) {
                // native query support
                if (SolrQueryIndex.NATIVE_SOLR_QUERY.equals(pr.propertyName) || SolrQueryIndex.NATIVE_LUCENE_QUERY.equals(pr.propertyName)) {
                    String nativeQueryString = String.valueOf(pr.first.getValue(pr.first.getType()));
                    if (isSupportedHttpRequest(nativeQueryString)) {
                        // pass through the native HTTP Solr request
                        String requestHandlerString = nativeQueryString.substring(0, nativeQueryString.indexOf('?'));
                        if (!"select".equals(requestHandlerString)) {
                            if (requestHandlerString.charAt(0) != '/') {
                                requestHandlerString = "/" + requestHandlerString;
                            }
                            solrQuery.setRequestHandler(requestHandlerString);
                        }
                        String parameterString = nativeQueryString.substring(nativeQueryString.indexOf('?') + 1);
                        for (String param : parameterString.split("&")) {
                            String[] kv = param.split("=");
                            if (kv.length != 2) {
                                throw new RuntimeException("Unparsable native HTTP Solr query");
                            } else {
                                if ("stream.body".equals(kv[0])) {
                                    kv[0] = "q";
                                    String mltFlString = "mlt.fl=";
                                    int mltFlIndex = parameterString.indexOf(mltFlString);
                                    if (mltFlIndex > -1) {
                                        int beginIndex = mltFlIndex + mltFlString.length();
                                        int endIndex = parameterString.indexOf('&', beginIndex);
                                        String fields;
                                        if (endIndex > beginIndex) {
                                            fields = parameterString.substring(beginIndex, endIndex);
                                        } else {
                                            fields = parameterString.substring(beginIndex);
                                        }
                                        kv[1] = "_query_:\"{!dismax qf=" + fields + " q.op=OR}" + kv[1] + "\"";
                                    }
                                }
                                solrQuery.setParam(kv[0], kv[1]);
                            }
                        }
                        return solrQuery;
                    } else {
                        queryBuilder.append(nativeQueryString);
                    }
                } else {
                    if (!configuration.useForPropertyRestrictions() // Solr index not used for properties
                            || pr.propertyName.contains("/") // no child-level property restrictions
                            || "rep:excerpt".equals(pr.propertyName) // rep:excerpt is handled by the query engine
                            || configuration.getIgnoredProperties().contains(pr.propertyName) // property is explicitly ignored
                            ) {
                        continue;
                    }

                    String first = null;
                    if (pr.first != null) {
                        first = partialEscape(String.valueOf(pr.first.getValue(pr.first.getType()))).toString();
                    }
                    String last = null;
                    if (pr.last != null) {
                        last = partialEscape(String.valueOf(pr.last.getValue(pr.last.getType()))).toString();
                    }

                    String prField = configuration.getFieldForPropertyRestriction(pr);
                    CharSequence fieldName = partialEscape(prField != null ?
                            prField : pr.propertyName);
                    if ("jcr\\:path".equals(fieldName.toString())) {
                        queryBuilder.append(configuration.getPathField());
                        queryBuilder.append(':');
                        queryBuilder.append(first);
                    } else {
                        if (pr.first != null && pr.last != null && pr.first.equals(pr.last)) {
                            queryBuilder.append(fieldName).append(':');
                            queryBuilder.append(first);
                        } else if (pr.first == null && pr.last == null) {
                            if (!queryBuilder.toString().contains(fieldName + ":")) {
                                queryBuilder.append(fieldName).append(':');
                                queryBuilder.append('*');
                            }
                        } else if ((pr.first != null && pr.last == null) || (pr.last != null && pr.first == null) || (!pr.first.equals(pr.last))) {
                            // TODO : need to check if this works for all field types (most likely not!)
                            queryBuilder.append(fieldName).append(':');
                            queryBuilder.append(createRangeQuery(first, last, pr.firstIncluding, pr.lastIncluding));
                        } else if (pr.isLike) {
                            // TODO : the current parameter substitution is not expected to work well
                            queryBuilder.append(fieldName).append(':');
                            queryBuilder.append(partialEscape(String.valueOf(pr.first.getValue(pr.first.getType())).replace('%', '*').replace('_', '?')));
                        } else {
                            throw new RuntimeException("[unexpected!] not handled case");
                        }
                    }
                }
                queryBuilder.append(" ");
            }
        }

        if (configuration.useForPrimaryTypes()) {
            String[] pts = filter.getPrimaryTypes().toArray(new String[filter.getPrimaryTypes().size()]);
            for (int i = 0; i < pts.length; i++) {
                String pt = pts[i];
                if (i == 0) {
                    queryBuilder.append("(");
                }
                if (i > 0 && i < pts.length) {
                    queryBuilder.append("OR ");
                }
                queryBuilder.append("jcr\\:primaryType").append(':').append(partialEscape(pt)).append(" ");
                if (i == pts.length - 1) {
                    queryBuilder.append(")");
                    queryBuilder.append(' ');
                }
            }
        }

        if (configuration.useForPathRestrictions()) {
            Filter.PathRestriction pathRestriction = filter.getPathRestriction();
            if (pathRestriction != null) {
                String path = purgePath(filter);
                String fieldName = configuration.getFieldForPathRestriction(pathRestriction);
                if (fieldName != null) {
                    queryBuilder.append(fieldName);
                    queryBuilder.append(':');
                    queryBuilder.append(path);
                }
            }
        }

        if (queryBuilder.length() == 0) {
            queryBuilder.append("*:*");
        }
        String escapedQuery = queryBuilder.toString();
        solrQuery.setQuery(escapedQuery);

        if (log.isDebugEnabled()) {
            log.debug("JCR query {} has been converted to Solr query {}",
                    filter.getQueryStatement(), solrQuery.toString());
        }

        return solrQuery;
    }

    private static CharSequence partialEscape(CharSequence s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '!' || c == '(' || c == ')' ||
                    c == ':' || c == '^' || c == '[' || c == ']' || c == '/' ||
                    c == '{' || c == '}' || c == '~' || c == '*' || c == '?' ||
                    c == '-' || c == ' ') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb;
    }

    private static String parseFullTextExpression(FullTextExpression ft, final OakSolrConfiguration configuration) {
        final StringBuilder fullTextString = new StringBuilder();
        ft.accept(new FullTextVisitor() {

            @Override
            public boolean visit(FullTextOr or) {
                fullTextString.append('(');
                for (int i = 0; i < or.list.size(); i++) {
                    if (i > 0 && i < or.list.size()) {
                        fullTextString.append(" OR ");
                    }
                    FullTextExpression e = or.list.get(i);
                    String orTerm = parseFullTextExpression(e, configuration);
                    fullTextString.append(orTerm);
                }
                fullTextString.append(')');
                fullTextString.append(' ');
                return true;
            }

            @Override
            public boolean visit(FullTextContains contains) {
                return contains.getBase().accept(this);
            }

            @Override
            public boolean visit(FullTextAnd and) {
                fullTextString.append('(');
                for (int i = 0; i < and.list.size(); i++) {
                    if (i > 0 && i < and.list.size()) {
                        fullTextString.append(" AND ");
                    }
                    FullTextExpression e = and.list.get(i);
                    String andTerm = parseFullTextExpression(e, configuration);
                    fullTextString.append(andTerm);
                }
                fullTextString.append(')');
                fullTextString.append(' ');
                return true;
            }

            @Override
            public boolean visit(FullTextTerm term) {
                if (term.isNot()) {
                    fullTextString.append('-');
                }
                String p = term.getPropertyName();
                if (p != null && p.indexOf('/') >= 0) {
                    p = getName(p);
                }
                if (p == null || "*".equals(p)) {
                    p = configuration.getCatchAllField();
                }
                fullTextString.append(partialEscape(p));
                fullTextString.append(':');
                String termText = term.getText();
                if (termText.indexOf(' ') > 0) {
                    fullTextString.append('"');
                }
                fullTextString.append(termText.replace("/", "\\/").replace(":", "\\:"));
                if (termText.indexOf(' ') > 0) {
                    fullTextString.append('"');
                }
                String boost = term.getBoost();
                if (boost != null) {
                    fullTextString.append('^');
                    fullTextString.append(boost);
                }
                fullTextString.append(' ');
                return true;
            }
        });
        return fullTextString.toString();
    }

    private static boolean isSupportedHttpRequest(String nativeQueryString) {
        // the query string starts with ${supported-handler.selector}?
        return nativeQueryString.matches("(mlt|query|select|get)\\\\?.*");
    }

    private static void setDefaults(SolrQuery solrQuery, OakSolrConfiguration configuration) {
        solrQuery.setParam("q.op", "AND");
        solrQuery.setParam("fl", configuration.getPathField() + " score");
        String catchAllField = configuration.getCatchAllField();
        if (catchAllField != null && catchAllField.length() > 0) {
            solrQuery.setParam("df", catchAllField);
        }

        solrQuery.setParam("rows", String.valueOf(configuration.getRows()));
    }

    private static String createRangeQuery(String first, String last, boolean firstIncluding, boolean lastIncluding) {
        // TODO : handle inclusion / exclusion of bounds
        return "[" + (first != null ? first : "*") + " TO " + (last != null ? last : "*") + "]";
    }

    private static String purgePath(Filter filter) {
        return partialEscape(filter.getPath()).toString();
    }

}
