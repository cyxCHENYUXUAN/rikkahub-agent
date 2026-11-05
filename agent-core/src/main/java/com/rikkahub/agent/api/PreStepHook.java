package com.rikkahub.agent.api;

/**
 * Synchronous waterfall hook run before a step. Call {@link Chain#next()}
 * once to continue, or rewrite/reject the working copy to stop the chain.
 * Returning without any decision blocks the step.
 */
public interface PreStepHook {
    void handle(PreStepContext context, Chain chain);

    interface Chain {
        void next();
    }
}
