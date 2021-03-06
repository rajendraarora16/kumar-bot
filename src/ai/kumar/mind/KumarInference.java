
package ai.kumar.mind;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.eclipse.jetty.util.log.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import ai.kumar.DAO;
import ai.kumar.json.JsonPath;
import ai.kumar.server.api.kumar.ConsoleService;
import ai.kumar.tools.TimeoutMatcher;
import alice.tuprolog.InvalidTheoryException;
import alice.tuprolog.Prolog;
import alice.tuprolog.SolveInfo;
import alice.tuprolog.Theory;

/**
 * Automated reasoning systems need inference methods to move from one proof state to another.
 * In Kumar reasoning we also have inference methods which retrieve data from external data sources
 * which is a data enrichment, but also inference methods which may filter, transform and reduce the
 * Kumar though elements in an argument. Inferences are applied step by step and act like a data stream.
 * Concatenated inferences are like piped commands or stream lambdas. Each time an inferece is applied
 * an unification step is done first to instantiate the variables inside an inference with the thought
 * argument from the steps before.
 */
public class KumarInference {
    
    public static enum Type {
        console, flow, memory, javascript, prolog;
        public int getSubscore() {
            return this.ordinal() + 1;
        }
    }
    
    private JSONObject json;
    private static final ScriptEngine javascript =  new ScriptEngineManager().getEngineByName("nashorn");
    
    /**
     * Instantiate an inference with the inference description. The description should usually contain two
     * properties:
     *   type: the name of the inference type. This can also be considered as the 'program language' of the inference
     *   expression: the description of the inference transformation. You can consider that beeing the 'program' of the inference
     * @param json the inference description
     */
    public KumarInference(JSONObject json) {
        this.json = json;
    }

    public static JSONObject simpleMemoryProcess(String expression) {
        JSONObject json = new JSONObject(true);
        json.put("type", Type.memory.name());
        json.put("expression", expression);
        return json;
    }
    
    /**
     * Inferences may have different types. Each type selects inference methods inside the inference description.
     * While the inference description mostly has only one other attribute, the "expression" it might have more.
     * @return the inference type
     */
    public Type getType() {
        return this.json.has("type") ? Type.valueOf(this.json.getString("type")) : Type.console;
    }
    
    /**
     * The inference expression is the 'program code' which describes what to do with thought arguments.
     * This program code is therefore like a lambda expression which takes a table-like data structure
     * and produces a new table-like data structure as a result. New data structures are then later appended to
     * thought arguments and therefore expand the argument with that one new inference result
     * @return the inference expression
     */
    public String getExpression() {
        return this.json.has("expression") ? this.json.getString("expression") : "";
    }
    
    public JSONObject getDefinition() {
        return this.json.has("definition") ? this.json.getJSONObject("definition") : null;
    }
    
    private final static KumarProcedures flowProcedures = new KumarProcedures();
    private final static KumarProcedures memoryProcedures = new KumarProcedures();
    private final static KumarProcedures javascriptProcedures = new KumarProcedures();
    private final static KumarProcedures prologProcedures = new KumarProcedures();
    static {
        flowProcedures.put(Pattern.compile("SQUASH"), (flow, matcher) -> {
            // perform a full mindmeld
            if (flow == null) return new KumarThought();
            KumarThought squashedArgument = flow.mindmeld(true);
            flow.amnesia();
            return squashedArgument;
        });
        flowProcedures.put(Pattern.compile("FIRST"), (flow, matcher) -> {
            // extract only the first row of a thought
            KumarThought recall = flow == null ? new KumarThought() : flow.rethink(); // removes/replaces the latest thought from the flow!
            if (recall.getCount() > 0) recall.setData(new JSONArray().put(recall.getData().getJSONObject(0)));
            return recall;
        });
        flowProcedures.put(Pattern.compile("REST"), (flow, matcher) -> {
            // remove the first row of a thought and return the remaining
            KumarThought recall = flow == null ? new KumarThought() : flow.rethink(); // removes/replaces the latest thought from the flow!
            if (recall.getCount() > 0) recall.getData().remove(0);
            return recall;
        });
        memoryProcedures.put(Pattern.compile("SET\\h+?([^=]*?)\\h+?=\\h+?([^=]*)\\h*?"), (flow, matcher) -> {
            String remember = matcher.group(1), matching = matcher.group(2);
            return see(flow, flow.unify("%1% AS " + remember, false, 0), flow.unify(matching, false, 0), Pattern.compile("(.*)"));
        });
        memoryProcedures.put(Pattern.compile("SET\\h+?([^=]*?)\\h+?=\\h+?([^=]*?)\\h+?MATCHING\\h+?(.*)\\h*?"), (flow, matcher) -> {
            String remember = matcher.group(1), matching = matcher.group(2), pattern = matcher.group(3);
            return see(flow, flow.unify(remember, false, 0), flow.unify(matching, false, 0), Pattern.compile(flow.unify(pattern, false, 0)));
        });
        memoryProcedures.put(Pattern.compile("CLEAR\\h+?(.*)\\h*?"), (flow, matcher) -> {
            String clear = matcher.group(1);
            return see(flow, "%1% AS " + flow.unify(clear, false, 0), "", Pattern.compile("(.*)"));
        });
        memoryProcedures.put(Pattern.compile("IF\\h+?([^=]*)\\h*?"), (flow, matcher) -> {
            String expect = matcher.group(1);
            KumarThought t = see(flow, "%1% AS EXPECTED", flow.unify(expect, false, 0), Pattern.compile("(.+)"));
            if (t.isFailed() || t.hasEmptyObservation("EXPECTED")) return new KumarThought(); // empty thought -> fail
            return t;
        });
        memoryProcedures.put(Pattern.compile("IF\\h+?([^=]*?)\\h*=\\h*([^=]*)\\h*?"), (flow, matcher) -> {
            String expect = matcher.group(1), matching = matcher.group(2);
            KumarThought t = see(flow, "%1% AS EXPECTED", flow.unify(expect, false, 0), Pattern.compile(flow.unify(matching, false, 0)));
            if (t.isFailed() || t.hasEmptyObservation("EXPECTED")) return new KumarThought(); // empty thought -> fail
            return t;
        });
        memoryProcedures.put(Pattern.compile("NOT\\h*"), (flow, matcher) -> {
            KumarThought t = see(flow, "%1% AS EXPECTED", "", Pattern.compile("(.*)"));
            return new KumarThought().addObservation("REJECTED", "");
        });
        memoryProcedures.put(Pattern.compile("NOT\\h+?([^=]*)\\h*?"), (flow, matcher) -> {
            String reject = matcher.group(1);
            KumarThought t = see(flow, "%1% AS EXPECTED", flow.unify(reject, false, 0), Pattern.compile("(.*)"));
            if (t.isFailed() || t.hasEmptyObservation("EXPECTED")) return new KumarThought().addObservation("REJECTED", reject);
            return new KumarThought(); // empty thought -> fail
        });
        memoryProcedures.put(Pattern.compile("NOT\\h+?([^=]*?)\\h*=\\h*([^=]*)\\h*?"), (flow, matcher) -> {
            String reject = matcher.group(1), matching = matcher.group(2);
            KumarThought t = see(flow, "%1% AS EXPECTED", flow.unify(reject, false, 0), Pattern.compile(flow.unify(matching, false, 0)));
            if (t.isFailed() || t.hasEmptyObservation("EXPECTED")) return new KumarThought().addObservation("REJECTED(" + matching + ")", reject);
            return new KumarThought(); // empty thought -> fail
        });
        javascriptProcedures.put(Pattern.compile("(.*)"), (flow, matcher) -> {
            String term = matcher.group(1);
            try {
                StringWriter stdout = new StringWriter();
                javascript.getContext().setWriter(new PrintWriter(stdout));
                javascript.getContext().setErrorWriter(new PrintWriter(stdout));
                Object o = javascript.eval(flow.unify(term, false));
                String bang = o == null ? "" : o.toString().trim();
                if (bang.length() == 0) bang = stdout.getBuffer().toString().trim();
                return new KumarThought().addObservation("!", bang);
            } catch (Throwable e) {
                Log.getLog().debug(e);
                return new KumarThought(); // empty thought -> fail
            }
        });
        prologProcedures.put(Pattern.compile("(.*)"), (flow, matcher) -> {
            String term = matcher.group(1);
            try {
                Prolog engine = new Prolog();
                try {
                    engine.setTheory(new Theory(term));
                    SolveInfo solution = engine.solve("associatedWith(X, Y, Z)."); // example
                    if (solution.isSuccess()) { // example
                        System.out.println(solution.getTerm("X"));
                        System.out.println(solution.getTerm("Y"));
                        System.out.println(solution.getTerm("Z"));
                    }
                } catch (InvalidTheoryException ex) {
                    DAO.log("invalid theory - line: "+ex.line);
                } catch (Exception ex){
                    DAO.log("invalid theory.");
                }
                
                return new KumarThought().addObservation("!", "");
            } catch (Throwable e) {
                Log.getLog().debug(e);
                return new KumarThought(); // empty thought -> fail
            }
        });
        // more procedures:
        // - map/reduce to enable loops
        // - sort asc/dec
        // - stack + join
        // - register (write temporary variable)
        // - compute (write computation into new field)
        // - cut (to stop backtracking)
    }
    
    /**
     * "see" defines a new thought based on the names given in the "transferExpr" and retrieved using the content of
     * a variable in the "expr" expression using a matching in the given pattern. It can be used to check if something
     * learned in the flow matches against a known pattern. When the matching is successful, that defines a new knowledge
     * pieces that are stored in the resulting thought thus extending an argument with new insights.
     * The "transferExpr" must be constructed using variables of the name schema "%1%", "%2%", .. which contain matches
     * of the variables from the expr retrieval in the flow with the pattern.
     * @param flow the argument flow
     * @param transferExpr SQL-like transfer expression, like "a AS akk, b AS bit". These defined variables stored in the flow as next thought
     * @param expr the name of a variable entity in the argument flow. The content of that variable is matched in the pattern
     * @param pattern the
     * @return a new thought containing variables from the matcher in the pattern
     */
    private static final KumarThought see(KumarArgument flow, String transferExpr, String expr, Pattern pattern) {
        // example: see $1$ as idea from ""
        KumarThought nextThought = new KumarThought();
        try {
            Matcher m = pattern.matcher(flow.unify(expr, false, 0));
            int gc = -1;
            if (new TimeoutMatcher(m).matches()) {
                KumarTransfer transfer = new KumarTransfer(transferExpr);
                JSONObject choice = new JSONObject();
                if ((gc = m.groupCount()) > 0) {
                    for (int i = 0; i < gc; i++) choice.put("%" + (i+1) + "%", m.group(i));
                } else {
                    choice.put("%1%", expr);
                }
                JSONObject seeing = transfer.extract(choice);
                for (String key: seeing.keySet()) {
                    String observed = seeing.getString(key);
                    nextThought.addObservation(key, observed);
                }
            }
        } catch (PatternSyntaxException e) {
            e.printStackTrace();
        }
        return nextThought; // an empty thought is a fail signal
    }
    
    
    /**
     * The inference must be applicable to thought arguments. This method executes the inference process on an existing 
     * argument and produces another thought which may or may not be appended to the given argument to create a full
     * argument proof. Within this method also data from the argument is unified with the inference variables
     * @param flow
     * @return a new thought as result of the inference
     */
    public KumarThought applyProcedures(KumarArgument flow) {
        Type type = this.getType();
        if (type == KumarInference.Type.console) {
            String expression = this.getExpression();
            // we have two ways to define a console rule:
            // with a "defintion" object which should have an "url" and "path" defined
            // with a "expression" object which has a kumar db access string included
            if (expression.length() == 0) {
                // this might have an anonymous console rule inside
                JSONObject definition = this.getDefinition();
                if (definition == null) return new KumarThought();
                
                // execute the console rule right here
                KumarThought json = new KumarThought();
                try {
                    String url = flow.unify(definition.getString("url"), true);
                    String path = flow.unify(definition.getString("path"), false);
                    JSONTokener serviceResponse = new JSONTokener(new ByteArrayInputStream(ConsoleService.loadData(url)));
                    JSONArray data = JsonPath.parse(serviceResponse, path);
                    if (data != null) json.setData(new KumarTransfer("*").conclude(data));
                    json.setHits(json.getCount());
                } catch (Throwable e) {
                    //e.printStackTrace(); // probably a time-out
                }
                return json;
                
            } else {
                try {return ConsoleService.dbAccess.deduce(flow, flow.unify(expression, false));} catch (Exception e) {}
            }
        }
        if (type == KumarInference.Type.flow) {
            String expression = flow.unify(this.getExpression(), false);
            try {return flowProcedures.deduce(flow, expression);} catch (Exception e) {}
        }
        if (type == KumarInference.Type.memory) {
            String expression = flow.unify(this.getExpression(), false);
            try {return memoryProcedures.deduce(flow, expression);} catch (Exception e) {}
        }
        if (type == KumarInference.Type.javascript) {
            String expression = flow.unify(this.getExpression(), false);
            try {return javascriptProcedures.deduce(flow, expression);} catch (Exception e) {}
        }
        if (type == KumarInference.Type.prolog) {
            String expression = flow.unify(this.getExpression(), false);
            try {return prologProcedures.deduce(flow, expression);} catch (Exception e) {}
        }
        // maybe the argument is not applicable, then an empty thought is produced (which means a 'fail')
        return new KumarThought();
}
    
    public String toString() {
        return this.getJSON().toString();
    }
    
    public JSONObject getJSON() {
        return this.json;
    }
}
