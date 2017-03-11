package org.anarres.cpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;

import javax.annotation.Nonnull;
import java.util.ArrayList;

public abstract class TargetStates {
    public static TargetStates afterSkip(final ArrayList<Token> tokens, TargetStates next) {
        for (int i = tokens.size() - 1; i >= 0; i--) {
             next = new AfterSkip(tokens.get(i), next);
        }
        return next;
    }
    public abstract TargetStates whenSkip(Token token);
    public abstract boolean matches(Environment environment, FList<TokenS> rest);
}

class TerminalState extends TargetStates {
    @Override
    public TargetStates whenSkip(Token token) {
        return null;
    }

    @Override
    public boolean matches(Environment environment, FList<TokenS> rest) {
        return rest.isEmpty();
    }

    @Override
    public String toString() {
        return "[]";
    }
}

class AfterSkip extends TargetStates {
    @Nonnull
    public final Token token;
    @Nonnull
    public final TargetStates next;

    public AfterSkip(@Nonnull Token token, @Nonnull TargetStates next) {
        this.token = token;
        this.next = next;
    }

    @Override
    public TargetStates whenSkip(Token token) {
        if (this.token.equals(token)) {
            return next;
        }
        return null;
    }

    @Override
    public boolean matches(Environment environment, FList<TokenS> rest) {
        return false;
    }

    @Override
    public String toString() {
        JsonArray tokens = new JsonArray();
        AfterSkip cur = this;
        for (;;) {
            tokens.add(new JsonPrimitive(cur.token.getText()));
            if (cur.next instanceof AfterSkip) {
                cur = (AfterSkip)cur.next;
            } else {
                break;
            }
        }
        return "Skip " + tokens + "\n" + cur.next.toString();
    }
}

class EnvAndRest extends TargetStates {
    @Nonnull
    public final Environment environment;
    @Nonnull
    public final FList<TokenS> rest;
    @Nonnull
    public final TargetStates fallback;

    public EnvAndRest(@Nonnull Environment environment, @Nonnull FList<TokenS> rest, @Nonnull TargetStates fallback) {
        this.environment = environment;
        this.rest = rest;
        this.fallback = fallback;
    }

    @Override
    public TargetStates whenSkip(Token token) {
        return fallback.whenSkip(token);
    }

    @Override
    public boolean matches(Environment environment, FList<TokenS> rest) {
        if (environment.equals(this.environment) && rest.equals(this.rest)) {
            return true;
        }
        return fallback.matches(environment, rest);
    }

    @Override
    public String toString() {
        return "Env " + environment + " Rest " + rest + "\n" + fallback;
    }
}