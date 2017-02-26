package org.anarres.cpp;

import org.pcollections.*;

import java.util.Collection;
/*
public class Backward {
    private final Strategy[] strategies = new Strategy[]{new Preserve(), new CancelRoot(), new CancelAll()};

    public PSequence<PSequence<TokenS>> backward(PSequence<PSequence<TokenS>> changes, ActionSequence actions, PSequence<Token> target) {
        PStack<PSequence<TokenS>> leftChanges = reverse(changes); // Changes on the left in reversed order
        PStack<PSequence<TokenS>> rightChanges = Empty.stack();

        PStack<Token> leftTarget = reverse(target); // Target on the left in reverse order
        PStack<Token> rightTarget = Empty.stack();

        for (int i = actions.actions.size()-1;i >=0; i--) {
            Action action = actions.actions.get(i);
            Environment beforeEnv = actions.environments.get(i);
            Environment afterEnv = actions.environments.get(i+1);

            final int lenProcessed = action.processed().size();
            final int lenSkipped = action.skipped().size();
            PSequence<PSequence<TokenS>> processedChanges = rightChanges.subList(0, lenProcessed);
            PSequence<PSequence<TokenS>> skippedChanges = reverse(leftChanges.subList(0, lenSkipped));
            PStack<PSequence<TokenS>> newLeftChanges = leftChanges.subList(lenSkipped);

            for (Strategy strategy: strategies) {
                PSequence<PSequence<TokenS>> originalChanges = strategy.back(action, processedChanges);
            }
        }
    }

    public static <E> PStack<E> reverse(PSequence<? extends E> list) {
        PStack<E> rev = ConsPStack.empty();
        for (E e: list) {
            rev = rev.plus(e);
        }
        return rev;
    }

    // Efficient implementation of stack eqauls
    public static <T> boolean equals(PStack<T> stack1, PStack<T> stack2) {
        if (stack1.size() != stack2.size()) {
            return false;
        }

        while (!stack1.isEmpty()) {
            if (stack1 == stack2) {
                return true;
            }
            if (!stack1.get(0).equals(stack2.get(0))) {
                return false;
            }
            stack1 = stack1.subList(1);
            stack2 = stack2.subList(1);
        }
        return true;
    }

    public static PSequence<PSequence<TokenS>> makeReplaceChanges(PSequence<TokenS> from, PSequence<TokenS> to) {
        if (from.isEmpty() && to.isEmpty()) return Empty.vector();
        if (from.isEmpty()) {
            throw new RuntimeException("unable to change from empty to non-empty");
        }
        PStack<PSequence<TokenS>> result = Empty.stack();
        for (int i = 0; i < from.size() - 1; i++) {
            result = result.plus(Empty.vector());
        }
        return result.plus(to);
    }
}

interface Strategy {
    PSequence<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges);
}

class CancelAll implements Strategy {
    @Override
    public PSequence<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges) {
        PVector<TokenS> tokens = Empty.vector();
        for (PSequence<TokenS> change: processedChanges) {
            for (TokenS tokenS: change) {
                tokens = tokens.plus(new TokenS(tokenS.token, tokenS.disables.minusAll(replace.disables)));
            }
        }
        return Backward.makeReplaceChanges()
    }
}

class CancelRoot implements Strategy {

}

class Preserve implements Strategy {

}
*/