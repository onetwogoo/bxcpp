/*
 * Anarres C Preprocessor
 * Copyright (c) 2007-2015, Shevek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.anarres.cpp;

import java.io.IOException;
import java.util.*;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

import jdk.nashorn.internal.parser.Lexer;
import org.pcollections.Empty;
import org.pcollections.HashTreePBag;
import org.pcollections.PSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.anarres.cpp.Token.*;

/* This source should always be active, since we don't expand macros
 * in any inactive context. */
/* pp */ class MacroTokenSource extends Source {

    private static final Logger LOG = LoggerFactory.getLogger(MacroTokenSource.class);
    private final Macro macro;
    private final Iterator<Token> tokens;	/* Pointer into the macro.  */
    private final PSet<String> disables;

    private final List<Argument> args;	/* { unexpanded, expanded } */

    private Iterator<TokenS> arg;	/* "current expansion" */
    private boolean insideArgument;

    private List<MapSeg> mapping;
    public List<TokenS> produced;
    public int producedIndex;

    /* pp */ MacroTokenSource(@Nonnull Macro m, @Nonnull List<Argument> args, List<MapSeg> mapping, PSet<String> disables) throws IOException, LexerException {
        this.macro = m;
        this.tokens = m.getTokens().iterator();
        this.args = args;
        this.mapping = mapping;
        this.arg = null;
        this.disables = disables;

        produced = new ArrayList<>();
        for (TokenS token = _token(); token.token.getType() != Token.EOF; token = _token()) {
            produced.add(token);
            if (!insideArgument) {
                mapping.add(new New(Collections.singletonList(token.token)));
            }
        }
    }

    /* XXX Called from Preprocessor [ugly]. */
    /* pp */ static void escape(@Nonnull StringBuilder buf, @Nonnull CharSequence cs) {
        if (buf == null)
            throw new NullPointerException("Buffer was null.");
        if (cs == null)
            throw new NullPointerException("CharSequence was null.");
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            switch (c) {
                case '\\':
                    buf.append("\\\\");
                    break;
                case '"':
                    buf.append("\\\"");
                    break;
                case '\n':
                    buf.append("\\n");
                    break;
                case '\r':
                    buf.append("\\r");
                    break;
                default:
                    buf.append(c);
            }
        }
    }

    private void concat(@Nonnull StringBuilder buf, @Nonnull Argument arg) {
        for (TokenS tok : arg) {
            buf.append(tok.token.getText());
        }
    }

    @Nonnull
    private Token stringify(@Nonnull Token pos, @Nonnull Argument arg) {
        StringBuilder buf = new StringBuilder();
        concat(buf, arg);
        // System.out.println("Concat: " + arg + " -> " + buf);
        StringBuilder str = new StringBuilder("\"");
        escape(str, buf);
        str.append("\"");
        // System.out.println("Escape: " + buf + " -> " + str);
        return new Token(STRING,
                pos.getLine(), pos.getColumn(),
                str.toString(), buf.toString());
    }

    /**
     * Returns true if the given argumentIndex is the last argument of a variadic macro.
     *
     * @param argumentIndex The index of the argument to inspect.
     * @return true if the given argumentIndex is the last argument of a variadic macro.
     */
    private boolean isVariadicArgument(@Nonnegative int argumentIndex) {
        if (!macro.isVariadic())
            return false;
        return argumentIndex == args.size() - 1;
    }

    /* At this point, we have consumed the first M_PASTE.
     * @see Macro#addPaste(Token) */
    private void paste(@Nonnull Token ptok)
            throws IOException,
            LexerException {
        // List<Token> out = new ArrayList<Token>();
        StringBuilder buf = new StringBuilder();
        // Token err = null;
        /* We know here that arg is null or expired,
         * since we cannot paste an expanded arg. */

        int count = 2;
        // While I hate auxiliary booleans, this does actually seem to be the simplest solution,
        // as it avoids duplicating all the logic around hasNext() in case COMMA.
        boolean comma = false;
        TOKEN:
        for (int i = 0; i < count; i++) {
            if (!tokens.hasNext()) {
                /* XXX This one really should throw. */
                error(ptok.getLine(), ptok.getColumn(),
                        "Paste at end of expansion");
                buf.append(' ').append(ptok.getText());
                break;
            }
            Token tok = tokens.next();
            // System.out.println("Paste " + tok);
            switch (tok.getType()) {
                case M_PASTE:
                    /* One extra to paste, plus one because the
                     * paste token didn't count. */
                    count += 2;
                    ptok = tok;
                    break;
                case M_ARG:
                    int idx = ((Integer) tok.getValue()).intValue();
                    Argument arg = args.get(idx);
                    if (comma && isVariadicArgument(idx) && arg.isEmpty()) {
                        // Ugly way to strip the comma.
                        buf.setLength(buf.length() - 1);
                    } else {
                        concat(buf, arg);
                    }
                    break;
                /* XXX Test this. */
                case CCOMMENT:
                case CPPCOMMENT:
                    // TODO: In cpp, -CC keeps these comments too,
                    // but turns all C++ comments into C comments.
                    break;
                case ',':
                    comma = true;
                    buf.append(tok.getText());
                    continue TOKEN;
                default:
                    buf.append(tok.getText());
                    break;
            }
            comma = false;
        }

        /* Push and re-lex. */
        /*
         StringBuilder		src = new StringBuilder();
         escape(src, buf);
         StringLexerSource	sl = new StringLexerSource(src.toString());
         */
        StringLexerSource sl = new StringLexerSource(buf.toString());

        /* XXX Check that concatenation produces a valid token. */
        arg = new SourceIterator(sl);
    }

    @Override
    public TokenS token() {
        if (producedIndex >= produced.size())
            return new TokenS(new Token(Token.EOF, -1, -1, ""), Empty.bag());
        return produced.get(producedIndex++);
    }

    private TokenS _token() throws IOException,LexerException {
        for (;;) {
            /* Deal with lexed tokens first. */

            if (arg != null) {
                if (arg.hasNext()) {
                    TokenS tok = arg.next();
                    /* XXX PASTE -> INVALID. */
                    assert tok.token.getType() != M_PASTE :
                            "Unexpected paste token";
                    tok = new TokenS(tok.token, tok.disables.plusAll(this.disables));
                    return tok;
                }
                arg = null;
                insideArgument = false;
            }

            if (!tokens.hasNext())
                return new TokenS(new Token(EOF, -1, -1, ""), Empty.bag());	/* End of macro. */

            Token tok = tokens.next();
            int idx;
            switch (tok.getType()) {
                case M_STRING:
                    /* Use the nonexpanded arg. */
                    idx = (Integer) tok.getValue();
                    return new TokenS(stringify(tok, args.get(idx)), HashTreePBag.from(disables));
                case M_ARG:
                    /* Expand the arg. */
                    idx = (Integer) tok.getValue();
                    // System.out.println("Pushing arg " + args.get(idx));
                    Argument argument = args.get(idx);
                    mapping.add(new Sub(argument.indicies, argument.actions));
                    insideArgument = true;
                    arg = argument.expansion();
                    break;
                case M_PASTE:
                    paste(tok);
                    break;
                default:
                    return new TokenS(tok, HashTreePBag.from(this.disables));
            }
        } /* for */

    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("expansion of ").append(macro.getName());
        Source parent = getParent();
        if (parent != null)
            buf.append(" in ").append(String.valueOf(parent));
        return buf.toString();
    }
}
