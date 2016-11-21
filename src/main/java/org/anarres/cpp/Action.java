package org.anarres.cpp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.tools.doclint.Env;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.*;

class Environment {
    public Map<String, Macro> macros;
    public Integer counter;
    public Stack<State> states;
    public Set<String> onceseenpaths;
    Environment(Map<String, Macro> macros,
        Stack<State> states, int counter, Set<String> onceseenpaths) {
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
}

abstract class Action {
}

class Skip extends Action {
    public TokenS token;

    public Skip(TokenS token) {
        this.token = token;
    }

    @Override
    public String toString() {
        return "Skip " + token;
    }
}

class Replace extends Action {
    public List<TokenS> old;
    public List<MapSeg> mapping;
    public Set<String> disables;

    public Replace(List<TokenS> old, List<MapSeg> mapping, Set<String> disables) {
        this.old = old;
        this.mapping = mapping;
        this.disables = disables;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Replace o=");
        for (TokenS token: old) {
            sb.append(token).append(" ");
        }
        sb.append(" mpn=");
        for (MapSeg seg:mapping) {
            sb.append(seg).append(" ");
        }
        sb.append(" dsbl=");
        for (String macro:disables) {
            sb.append(macro).append(" ");
        }
        return sb.toString();
    }
}

class TokenS {
    public Token token;
    public Set<String> disables;

    public TokenS(Token token, Set<String> disables) {
        this.token = token;
        this.disables = disables;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (token.getType() == Token.NL) sb.append("\\n");
        else sb.append(token.getText());
//        sb.append("{");
//        for (String macro:disables) {
//            sb.append(macro).append(" ");
//        }
//        sb.append("}");
        return sb.toString();
    }
}

abstract class MapSeg {
}

class Sub extends MapSeg {
    public List<Integer> indicies;
    public ActionSequence actions;

    public Sub(List<Integer> indicies, ActionSequence actions) {
        this.indicies = indicies;
        this.actions = actions;
    }
}

class New extends MapSeg {
    public List<Token> tokens;

    public New(List<Token> tokens) {
        this.tokens = tokens;
    }
}