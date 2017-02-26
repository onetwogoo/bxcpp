package org.anarres.cpp;

import com.google.gson.*;
import org.pcollections.*;

import java.util.*;

class Environment {
    public final Map<String, Macro> macros;
    public final int counter;
    public final Stack<State> states;
    public final List<String> onceseenpaths;

    Environment(Map<String, Macro> macros,
        Stack<State> states, int counter, List<String> onceseenpaths) {
        this.macros = macros;
        this.states = states;
        this.counter = counter;
        this.onceseenpaths = onceseenpaths;
    }

    @Override
    public boolean equals(Object obj) {
        Environment o = (Environment)obj;
        if (o.counter != this.counter)
            return false;
        if (!onceseenpaths.equals(o.onceseenpaths))
            return false;
        if (!macros.equals(o.macros))
            return false;
        if (states.size() != o.states.size())
            return false;
        for (int i = 0; i < states.size(); i ++) {
            if (!states.get(i).equals(o.states.get(i)))
                return false;
        }
        return true;
    }
}

class ActionSequence {
    public final List<Action> actions = new ArrayList<Action>();
    public final List<Environment> environments = new ArrayList<Environment>();

    public JsonArray toJson() {
        JsonArray result = new JsonArray();
        for (Action act: actions) {
            result.add(act.toJson());
        }
        return result;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}

interface Action {
    PVector<TokenS> skipped();
    PVector<TokenS> original();
    PVector<TokenS> processed();

    JsonObject toJson();
}

class Skip implements Action {
    public final TokenS token;

    public Skip(TokenS token) {
        this.token = token;
    }

    @Override
    public PVector<TokenS> skipped() {
        return TreePVector.singleton(token);
    }

    @Override
    public PVector<TokenS> original() {
        return TreePVector.empty();
    }

    @Override
    public PVector<TokenS> processed() {
        return TreePVector.empty();
    }

    public JsonObject toJson() {
        JsonObject result =  new JsonObject();
        result.add("skip", token.toJson());
        return result;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}

class Replace implements Action {
    public final PVector<TokenS> original;
    public final List<MapSeg> mapping;
    public final PSet<String> disables;
    public PVector<TokenS> processed;

    public Replace(PVector<TokenS> original, List<MapSeg> mapping, PSet<String> disables) {
        this.original = original;
        this.mapping = mapping;
        this.disables = disables;
    }

    @Override
    public PVector<TokenS> skipped() {
        return TreePVector.empty();
    }

    @Override
    public PVector<TokenS> original() {
        return original;
    }

    @Override
    public PVector<TokenS> processed() {
        if (processed == null) {
            processed = TreePVector.empty();
            PBag<String> disablesBag = HashTreePBag.from(disables);
            for (MapSeg seg : mapping) {
                if (seg instanceof New) {
                    for (Token tok : ((New) seg).tokens) {
                        processed = processed.plus(new TokenS(tok, disablesBag));
                    }
                } else {
                    Sub sub = ((Sub) seg);
                    for (Action action : sub.actions.actions) {
                        processed = processed.plusAll(action.processed());
                    }
                }
            }
        }
        return processed;
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        JsonArray replace = new JsonArray();
        for (TokenS token: original) {
            replace.add(token.toJson());
        }
        result.add("orgn", replace);
        JsonArray mpn = new JsonArray();
        for (MapSeg seg: mapping) {
            mpn.add(seg.toJson());
        }
        result.add("mpn", mpn);
        JsonArray dsbl = new JsonArray();
        for (String macro: disables) {
            dsbl.add(new JsonPrimitive(macro));
        }
        result.add("dsbl", dsbl);
        return result;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}

class TokenS {
    public final Token token;
    public final PBag<String> disables;

    public TokenS(Token token, PBag<String> disables) {
        this.token = token;
        this.disables = disables;
    }

    public JsonElement toJson() {
        if (disables.isEmpty()) {
            return new JsonPrimitive(token.getText());
        }
        JsonObject result = new JsonObject();
        result.addProperty("t", token.getText());
        JsonArray ds = new JsonArray();
        for (String macro :disables) {
            ds.add(new JsonPrimitive(macro));
        }
        result.add("d", ds);
        return result;
    }

    @Override
    public int hashCode() {
        return token.hashCode() ^ disables.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TokenS) {
            TokenS other = (TokenS)obj;
            return other.token.equals(token) && other.disables.equals(disables);
        }
        return false;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}

interface MapSeg {
    JsonObject toJson();
}

class Sub implements MapSeg {
    public final List<Integer> indicies;
    public final ActionSequence actions;

    public Sub(List<Integer> indicies, ActionSequence actions) {
        this.indicies = indicies;
        this.actions = actions;
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        JsonArray indx = new JsonArray();
        for (int i: indicies) {
            indx.add(new JsonPrimitive(i));
        }
        result.add("indx", indx);
        result.add("acts", actions.toJson());
        return result;
    }
}

class New implements MapSeg {
    public final List<Token> tokens;

    public New(List<Token> tokens) {
        this.tokens = tokens;
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        JsonArray toks = new JsonArray();
        for (Token t: tokens) {
            toks.add(new JsonPrimitive(t.getText()));
        }
        result.add("new", toks);
        return result;
    }
}
