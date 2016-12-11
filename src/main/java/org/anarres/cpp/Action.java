package org.anarres.cpp;

import com.google.gson.*;
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
    public List<Action> actions = new ArrayList<Action>();
    public List<Environment> environments = new ArrayList<Environment>();

    public JsonArray toJson() {
        JsonArray result = new JsonArray();
        for (Action act: actions) {
            result.add(act.toJson());
        }
        return result;
    }
}

interface Action {
    JsonObject toJson();
}

class Skip implements Action {
    public TokenS token;

    public Skip(TokenS token) {
        this.token = token;
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
    public List<TokenS> old;
    public List<MapSeg> mapping;
    public Set<String> disables;

    public Replace(List<TokenS> old, List<MapSeg> mapping, Set<String> disables) {
        this.old = old;
        this.mapping = mapping;
        this.disables = disables;
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        JsonArray replace = new JsonArray();
        for (TokenS token:old) {
            replace.add(token.toJson());
        }
        result.add("repl", replace);
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
    public Token token;
    public Set<String> disables;

    public TokenS(Token token, Set<String> disables) {
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
    public List<Integer> indicies;
    public ActionSequence actions;

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
    public List<Token> tokens;

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