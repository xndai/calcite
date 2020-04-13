/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.plan.volcano;

import org.apache.calcite.plan.RelHintsPropagator;
import org.apache.calcite.plan.RelOptListener;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptRuleOperandChildPolicy;
import org.apache.calcite.plan.SubstitutionRule;
import org.apache.calcite.rel.RelNode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <code>VolcanoRuleCall</code> implements the {@link RelOptRuleCall} interface
 * for VolcanoPlanner.
 */
public class VolcanoRuleCall extends RelOptRuleCall {
  //~ Instance fields --------------------------------------------------------

  protected final VolcanoPlanner volcanoPlanner;

  /**
   * List of {@link RelNode} generated by this call. For debugging purposes.
   */
  private List<RelNode> generatedRelList;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a rule call, internal, with array to hold bindings.
   *
   * @param planner Planner
   * @param operand First operand of the rule
   * @param rels    Array which will hold the matched relational expressions
   * @param nodeInputs For each node which matched with {@code matchAnyChildren}
   *                   = true, a list of the node's inputs
   */
  protected VolcanoRuleCall(
      VolcanoPlanner planner,
      RelOptRuleOperand operand,
      RelNode[] rels,
      Map<RelNode, List<RelNode>> nodeInputs) {
    super(planner, operand, rels, nodeInputs);
    this.volcanoPlanner = planner;
  }

  /**
   * Creates a rule call.
   *
   * @param planner Planner
   * @param operand First operand of the rule
   */
  VolcanoRuleCall(
      VolcanoPlanner planner,
      RelOptRuleOperand operand) {
    this(
        planner,
        operand,
        new RelNode[operand.getRule().operands.size()],
        ImmutableMap.of());
  }

  //~ Methods ----------------------------------------------------------------

  // implement RelOptRuleCall
  public void transformTo(RelNode rel, Map<RelNode, RelNode> equiv,
      RelHintsPropagator handler) {
    rel = handler.propagate(rels[0], rel);
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Transform to: rel#{} via {}{}", rel.getId(), getRule(),
          equiv.isEmpty() ? "" : " with equivalences " + equiv);
      if (generatedRelList != null) {
        generatedRelList.add(rel);
      }
    }
    try {
      // It's possible that rel is a subset or is already registered.
      // Is there still a point in continuing? Yes, because we might
      // discover that two sets of expressions are actually equivalent.

      if (LOGGER.isTraceEnabled()) {
        // Cannot call RelNode.toString() yet, because rel has not
        // been registered. For now, let's make up something similar.
        String relDesc =
            "rel#" + rel.getId() + ":" + rel.getRelTypeName();
        LOGGER.trace("call#{}: Rule {} arguments {} created {}",
            id, getRule(), Arrays.toString(rels), relDesc);
      }

      if (volcanoPlanner.getListener() != null) {
        RelOptListener.RuleProductionEvent event =
            new RelOptListener.RuleProductionEvent(
                volcanoPlanner,
                rel,
                this,
                true);
        volcanoPlanner.getListener().ruleProductionSucceeded(event);
      }

      final RelNode relCopy = rel;
      if (rels[0].getInputs().stream().anyMatch(n -> n == relCopy)) {
        volcanoPlanner.prune(rels[0]);
      }

      if (this.getRule() instanceof SubstitutionRule
          && ((SubstitutionRule) getRule()).autoPruneOld()) {
        volcanoPlanner.prune(rels[0]);
      }

      // Registering the root relational expression implicitly registers
      // its descendants. Register any explicit equivalences first, so we
      // don't register twice and cause churn.
      for (Map.Entry<RelNode, RelNode> entry : equiv.entrySet()) {
        volcanoPlanner.ensureRegistered(
            entry.getKey(), entry.getValue());
      }
      volcanoPlanner.ensureRegistered(rel, rels[0]);
      rels[0].getCluster().invalidateMetadataQuery();

      if (volcanoPlanner.getListener() != null) {
        RelOptListener.RuleProductionEvent event =
            new RelOptListener.RuleProductionEvent(
                volcanoPlanner,
                rel,
                this,
                false);
        volcanoPlanner.getListener().ruleProductionSucceeded(event);
      }
    } catch (Exception e) {
      throw new RuntimeException("Error occurred while applying rule "
          + getRule(), e);
    }
  }

  /**
   * Called when all operands have matched.
   */
  protected void onMatch() {
    assert getRule().matches(this);
    volcanoPlanner.checkCancel();
    try {
      if (volcanoPlanner.isRuleExcluded(getRule())) {
        LOGGER.debug("Rule [{}] not fired due to exclusion filter", getRule());
        return;
      }

      if (isRuleExcluded()) {
        LOGGER.debug("Rule [{}] not fired due to exclusion hint", getRule());
        return;
      }

      for (int i = 0; i < rels.length; i++) {
        RelNode rel = rels[i];
        RelSubset subset = volcanoPlanner.getSubset(rel);

        if (subset == null) {
          LOGGER.debug(
              "Rule [{}] not fired because operand #{} ({}) has no subset",
              getRule(), i, rel);
          return;
        }

        if (subset.set.equivalentSet != null) {
          LOGGER.debug(
              "Rule [{}] not fired because operand #{} ({}) belongs to obsolete set",
              getRule(), i, rel);
          return;
        }

        if (volcanoPlanner.prunedNodes.contains(rel)) {
          LOGGER.debug("Rule [{}] not fired because operand #{} ({}) has importance=0",
              getRule(), i, rel);
          return;
        }
      }

      if (LOGGER.isTraceEnabled()) {
        LOGGER.trace(
            "call#{}: Apply rule [{}] to {}",
            id, getRule(), Arrays.toString(rels));
      }

      if (volcanoPlanner.getListener() != null) {
        RelOptListener.RuleAttemptedEvent event =
            new RelOptListener.RuleAttemptedEvent(
                volcanoPlanner,
                rels[0],
                this,
                true);
        volcanoPlanner.getListener().ruleAttempted(event);
      }

      if (LOGGER.isDebugEnabled()) {
        this.generatedRelList = new ArrayList<>();
      }

      volcanoPlanner.ruleCallStack.push(this);
      try {
        getRule().onMatch(this);
      } finally {
        volcanoPlanner.ruleCallStack.pop();
      }

      if (LOGGER.isTraceEnabled()) {
        if (generatedRelList.isEmpty()) {
          LOGGER.trace("call#{} generated 0 successors.", id);
        } else {
          LOGGER.trace(
              "call#{} generated {} successors: {}",
              id, generatedRelList.size(), generatedRelList);
        }
        this.generatedRelList = null;
      }

      if (volcanoPlanner.getListener() != null) {
        RelOptListener.RuleAttemptedEvent event =
            new RelOptListener.RuleAttemptedEvent(
                volcanoPlanner,
                rels[0],
                this,
                false);
        volcanoPlanner.getListener().ruleAttempted(event);
      }
    } catch (Exception e) {
      throw new RuntimeException("Error while applying rule " + getRule()
          + ", args " + Arrays.toString(rels), e);
    }
  }

  /**
   * Applies this rule, with a given relational expression in the first slot.
   */
  void match(RelNode rel) {
    assert getOperand0().matches(rel) : "precondition";
    final int solve = 0;
    int operandOrdinal = getOperand0().solveOrder[solve];
    this.rels[operandOrdinal] = rel;
    matchRecurse(solve + 1);
  }

  /**
   * Recursively matches operands above a given solve order.
   *
   * @param solve Solve order of operand (&gt; 0 and &le; the operand count)
   */
  private void matchRecurse(int solve) {
    assert solve > 0;
    assert solve <= rule.operands.size();
    final List<RelOptRuleOperand> operands = getRule().operands;
    if (solve == operands.size()) {
      // We have matched all operands. Now ask the rule whether it
      // matches; this gives the rule chance to apply side-conditions.
      // If the side-conditions are satisfied, we have a match.
      if (getRule().matches(this)) {
        onMatch();
      }
    } else {
      final int operandOrdinal = operand0.solveOrder[solve];
      final int previousOperandOrdinal = operand0.solveOrder[solve - 1];
      boolean ascending = operandOrdinal < previousOperandOrdinal;
      final RelOptRuleOperand previousOperand =
          operands.get(previousOperandOrdinal);
      final RelOptRuleOperand operand = operands.get(operandOrdinal);
      final RelNode previous = rels[previousOperandOrdinal];

      final RelOptRuleOperand parentOperand;
      final Collection<? extends RelNode> successors;
      if (ascending) {
        assert previousOperand.getParent() == operand;
        if (previousOperand.getMatchedClass() != RelSubset.class
            && previous instanceof RelSubset) {
          throw new RuntimeException("RelSubset should not match with "
              + previousOperand.getMatchedClass().getSimpleName());
        }
        parentOperand = operand;
        final RelSubset subset = volcanoPlanner.getSubset(previous);
        successors = subset.getParentRels();
      } else {
        parentOperand = previousOperand;
        final int parentOrdinal = operand.getParent().ordinalInRule;
        final RelNode parentRel = rels[parentOrdinal];
        final List<RelNode> inputs = parentRel.getInputs();
        // if the child is unordered, then add all rels in all input subsets to the successors list
        // because unordered can match child in any ordinal
        if (parentOperand.childPolicy == RelOptRuleOperandChildPolicy.UNORDERED) {
          if (operand.getMatchedClass() == RelSubset.class) {
            successors = inputs;
          } else {
            List<RelNode> allRelsInAllSubsets = new ArrayList<>();
            Set<RelNode> duplicates = new HashSet<>();
            for (RelNode input : inputs) {
              if (!duplicates.add(input)) {
                // Ignore duplicate subsets
                continue;
              }
              RelSubset inputSubset = (RelSubset) input;
              for (RelNode rel : inputSubset.getRels()) {
                if (!duplicates.add(rel)) {
                  // Ignore duplicate relations
                  continue;
                }
                allRelsInAllSubsets.add(rel);
              }
            }
            successors = allRelsInAllSubsets;
          }
        } else if (operand.ordinalInParent < inputs.size()) {
          // child policy is not unordered
          // we need to find the exact input node based on child operand's ordinalInParent
          final RelSubset subset =
              (RelSubset) inputs.get(operand.ordinalInParent);
          if (operand.getMatchedClass() == RelSubset.class) {
            // Find all the sibling subsets that satisfy the traitSet of current subset.
            successors = subset.set.subsets.stream()
                .filter(s -> s.getTraitSet().satisfies(subset.getTraitSet()))
                .collect(Collectors.toList());
          } else {
            successors = subset.getRelList();
          }
        } else {
          // The operand expects parentRel to have a certain number
          // of inputs and it does not.
          successors = ImmutableList.of();
        }
      }

      for (RelNode rel : successors) {
        if (!operand.matches(rel)) {
          continue;
        }
        if (ascending && operand.childPolicy != RelOptRuleOperandChildPolicy.UNORDERED) {
          // We know that the previous operand was *a* child of its parent,
          // but now check that it is the *correct* child.
          if (previousOperand.ordinalInParent >= rel.getInputs().size()) {
            continue;
          }
          final RelSubset input =
              (RelSubset) rel.getInput(previousOperand.ordinalInParent);
          List<RelNode> inputRels = input.getRelList();
          if (!(previous instanceof RelSubset) && !inputRels.contains(previous)) {
            continue;
          }
        }

        // Assign "childRels" if the operand is UNORDERED.
        switch (parentOperand.childPolicy) {
        case UNORDERED:
          // Note: below is ill-defined. Suppose there's a union with 3 inputs,
          // and the rule is written as Union.class, unordered(...)
          // What should be provided for the rest 2 arguments?
          // RelSubsets? Random relations from those subsets?
          // For now, Calcite code does not use getChildRels, so the bug is just waiting its day
          if (ascending) {
            final List<RelNode> inputs = Lists.newArrayList(rel.getInputs());
            inputs.set(previousOperand.ordinalInParent, previous);
            setChildRels(rel, inputs);
          } else {
            List<RelNode> inputs = getChildRels(previous);
            if (inputs == null) {
              inputs = Lists.newArrayList(previous.getInputs());
            }
            inputs.set(operand.ordinalInParent, rel);
            setChildRels(previous, inputs);
          }
        }

        rels[operandOrdinal] = rel;
        matchRecurse(solve + 1);
      }
    }
  }
}
