/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.scriptfilter;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.fielddata.ScriptDocValues;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.MockScriptPlugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.scriptQuery;
import static org.hamcrest.Matchers.equalTo;

@ESIntegTestCase.ClusterScope(scope= ESIntegTestCase.Scope.SUITE)
public class ScriptQuerySearchIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(CustomScriptPlugin.class);
    }

    public static class CustomScriptPlugin extends MockScriptPlugin {

        @Override
        protected Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
            Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();

            scripts.put("doc['num1'].value", vars -> {
                Map<?, ?> doc = (Map) vars.get("doc");
                return doc.get("num1");
            });

            scripts.put("doc['num1'].value > 1", vars -> {
                Map<?, ?> doc = (Map) vars.get("doc");
                ScriptDocValues.Doubles num1 = (ScriptDocValues.Doubles) doc.get("num1");
                return num1.getValue() > 1;
            });

            scripts.put("doc['num1'].value > param1", vars -> {
                Integer param1 = (Integer) vars.get("param1");

                Map<?, ?> doc = (Map) vars.get("doc");
                ScriptDocValues.Doubles num1 = (ScriptDocValues.Doubles) doc.get("num1");
                return num1.getValue() > param1;
            });

            return scripts;
        }
    }

    @Override
    public Settings indexSettings() {
        return Settings.builder().put(super.indexSettings())
                // aggressive filter caching so that we can assert on the number of iterations of the script filters
                .put(IndexModule.INDEX_QUERY_CACHE_ENABLED_SETTING.getKey(), true)
                .put(IndexModule.INDEX_QUERY_CACHE_EVERYTHING_SETTING.getKey(), true)
                .build();
    }

    public void testCustomScriptBoost() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "type1", "1")
                .setSource(jsonBuilder().startObject().field("test", "value beck").field("num1", 1.0f).endObject())
                .get();
        flush();
        client().prepareIndex("test", "type1", "2")
                .setSource(jsonBuilder().startObject().field("test", "value beck").field("num1", 2.0f).endObject())
                .get();
        flush();
        client().prepareIndex("test", "type1", "3")
                .setSource(jsonBuilder().startObject().field("test", "value beck").field("num1", 3.0f).endObject())
                .get();
        refresh();

        logger.info("running doc['num1'].value > 1");
        SearchResponse response = client().prepareSearch()
                .setQuery(scriptQuery(new Script("doc['num1'].value > 1", ScriptType.INLINE, CustomScriptPlugin.NAME, null)))
                .addSort("num1", SortOrder.ASC)
                .addScriptField("sNum1", new Script("doc['num1'].value", ScriptType.INLINE, CustomScriptPlugin.NAME, null))
                .get();

        assertThat(response.getHits().totalHits(), equalTo(2L));
        assertThat(response.getHits().getAt(0).id(), equalTo("2"));
        assertThat(response.getHits().getAt(0).fields().get("sNum1").values().get(0), equalTo(2.0));
        assertThat(response.getHits().getAt(1).id(), equalTo("3"));
        assertThat(response.getHits().getAt(1).fields().get("sNum1").values().get(0), equalTo(3.0));

        Map<String, Object> params = new HashMap<>();
        params.put("param1", 2);

        logger.info("running doc['num1'].value > param1");
        response = client()
                .prepareSearch()
                .setQuery(scriptQuery(new Script("doc['num1'].value > param1", ScriptType.INLINE, CustomScriptPlugin.NAME, params)))
                .addSort("num1", SortOrder.ASC)
                .addScriptField("sNum1", new Script("doc['num1'].value", ScriptType.INLINE, CustomScriptPlugin.NAME, null))
                .get();

        assertThat(response.getHits().totalHits(), equalTo(1L));
        assertThat(response.getHits().getAt(0).id(), equalTo("3"));
        assertThat(response.getHits().getAt(0).fields().get("sNum1").values().get(0), equalTo(3.0));

        params = new HashMap<>();
        params.put("param1", -1);
        logger.info("running doc['num1'].value > param1");
        response = client()
                .prepareSearch()
                .setQuery(scriptQuery(new Script("doc['num1'].value > param1", ScriptType.INLINE, CustomScriptPlugin.NAME, params)))
                .addSort("num1", SortOrder.ASC)
                .addScriptField("sNum1", new Script("doc['num1'].value", ScriptType.INLINE, CustomScriptPlugin.NAME, null))
                .get();

        assertThat(response.getHits().totalHits(), equalTo(3L));
        assertThat(response.getHits().getAt(0).id(), equalTo("1"));
        assertThat(response.getHits().getAt(0).fields().get("sNum1").values().get(0), equalTo(1.0));
        assertThat(response.getHits().getAt(1).id(), equalTo("2"));
        assertThat(response.getHits().getAt(1).fields().get("sNum1").values().get(0), equalTo(2.0));
        assertThat(response.getHits().getAt(2).id(), equalTo("3"));
        assertThat(response.getHits().getAt(2).fields().get("sNum1").values().get(0), equalTo(3.0));
    }

    private static AtomicInteger scriptCounter = new AtomicInteger(0);

    public static int incrementScriptCounter() {
        return scriptCounter.incrementAndGet();
    }
}
