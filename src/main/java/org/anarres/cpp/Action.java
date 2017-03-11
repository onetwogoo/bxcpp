package org.anarres.cpp;

import com.google.gson.*;
import org.pcollections.*;

import java.util.*;

class Environment {
    public final PMap<String, Macro> macros;
    public final int counter;
    public final PStack<State> states;
    public final PSet<String> onceseenpaths;

    Environment(PMap<String, Macro> macros,
        PStack<State> states, int counter, PSet<String> onceseenpaths) {
        this.macros = macros;
        this.states = states;
        this.counter = counter;
        this.onceseenpaths = onceseenpaths;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Environment) {
            Environment o = (Environment) obj;
            if (o.counter != this.counter)
                return false;
            if (!onceseenpaths.equals(o.onceseenpaths))
                return false;
            if (!macros.equals(o.macros))
                return false;
            if (!states.equals(o.states))
                return false;
            return true;
        }
        return false;
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        JsonArray macs = new JsonArray();
        for (String name : macros.keySet()) {
            if (!name.startsWith("__"))
                macs.add(new JsonPrimitive(name));
        }
        if (macs.size() > 0) {
            result.add("macs", macs);
        }
        if (counter > 0) {
            result.addProperty("ctr", counter);
        }
        JsonArray stats = new JsonArray();
        for (State state: states) {
            stats.add(new JsonPrimitive(state.hashCode()));
        }
        result.add("stats", stats);
        JsonArray once = new JsonArray();
        for (String path: onceseenpaths) {
            once.add(new JsonPrimitive(path));
        }
        if (once.size() > 0) {
            result.add("once", once);
        }
        return result;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}

abstract class Action {
    public final Environment beforeEnv;
    public Action(Environment beforeEnv) {
        this.beforeEnv = beforeEnv;
    }

    abstract PVector<TokenS> skipped();
    abstract PVector<TokenS> original();
    abstract PVector<TokenS> processed();
    abstract JsonObject toJson();
}

class Skip extends Action {
    public final TokenS token;

    public Skip(Environment beforeEnv, TokenS token) {
        super(beforeEnv);
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

class Replace extends Action {
    public final PVector<TokenS> original;
    public final List<MapSeg> mapping;
    public final PSet<String> disables;
    private PVector<TokenS> processed;

    public Replace(Environment beforeEnv, PVector<TokenS> original, List<MapSeg> mapping, PSet<String> disables) {
        super(beforeEnv);
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
            processed = Empty.vector();
            for (MapSeg seg : mapping) {
                for (TokenS tokenS: seg.processed()) {
                    processed = processed.plus(new TokenS(tokenS.token, tokenS.disables.plusAll(disables)));
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
    PSequence<TokenS> processed();
    JsonObject toJson();
}

class Sub implements MapSeg {
    public final List<Integer> indicies;
    public final List<Action> actions;
    private PVector<TokenS> processed;

    public Sub(List<Integer> indicies, List<Action> actions) {
        this.indicies = indicies;
        this.actions = actions;
    }

    @Override
    public PSequence<TokenS> processed() {
        if (processed == null) {
            processed = Empty.vector();
            for (Action action: actions) {
                processed = processed.plusAll(action.skipped());
            }
        }
        return processed;
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        JsonArray idx = new JsonArray();
        for (int i: indicies) {
            idx.add(new JsonPrimitive(i));
        }
        result.add("idx", idx);
        JsonArray acts = new JsonArray();
        for (Action action: actions) {
            acts.add(action.toJson());
        }
        result.add("acts", acts);
        return result;
    }
}

class New implements MapSeg {
    public final List<Token> tokens;
    private PVector<TokenS> processed;

    public New(List<Token> tokens) {
        this.tokens = tokens;
    }

    @Override
    public PSequence<TokenS> processed() {
        if (processed == null) {
            processed = Empty.vector();
            for (Token token: tokens) {
                processed = processed.plus(new TokenS(token, Empty.bag()));
            }
        }
        return processed;
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
