/**
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.model.path.evaluator;


import static com.ibm.watsonhealth.fhir.model.path.FHIRPathDecimalValue.decimalValue;
import static com.ibm.watsonhealth.fhir.model.path.FHIRPathIntegerValue.integerValue;
import static com.ibm.watsonhealth.fhir.model.path.FHIRPathStringValue.EMPTY_STRING;
import static com.ibm.watsonhealth.fhir.model.path.FHIRPathStringValue.stringValue;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.empty;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.getBoolean;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.getInteger;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.getPrimitiveValue;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.getSingleton;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.getString;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.hasPrimitiveValue;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.isSingleton;
import static com.ibm.watsonhealth.fhir.model.path.util.FHIRPathUtil.singleton;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import com.ibm.watsonhealth.fhir.model.path.FHIRPathBaseVisitor;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathBooleanValue;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathLexer;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathNode;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathParser;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathParser.ExpressionContext;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathParser.ParamListContext;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathPrimitiveValue;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathTree;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathType;
import com.ibm.watsonhealth.fhir.model.path.exception.FHIRPathException;
import com.ibm.watsonhealth.fhir.model.path.function.FHIRPathFunction;
import com.ibm.watsonhealth.fhir.model.resource.Resource;
import com.ibm.watsonhealth.fhir.model.type.Element;

public class FHIRPathEvaluator {
    public static boolean DEBUG = false;
    
    public static final Collection<FHIRPathNode> SINGLETON_TRUE = singleton(FHIRPathBooleanValue.TRUE);
    public static final Collection<FHIRPathNode> SINGLETON_FALSE = singleton(FHIRPathBooleanValue.FALSE);
    
    private static final Map<String, ExpressionContext> EXPRESSION_CACHE = new ConcurrentHashMap<>();
    
    private final String expr;
    private final ExpressionContext expressionContext;
    
    private FHIRPathEvaluator(String expr, ExpressionContext expressionContext) {
        this.expr = expr;
        this.expressionContext = expressionContext;
    }
    
    public Collection<FHIRPathNode> evaluate() throws FHIRPathException {
        return evaluate(empty());
    }
    
    public Collection<FHIRPathNode> evaluate(Resource resource) throws FHIRPathException {
        return evaluate(FHIRPathTree.tree(resource));
    }
    
    public Collection<FHIRPathNode> evaluate(Element element) throws FHIRPathException {
        return evaluate(FHIRPathTree.tree(element));
    }
    
    public Collection<FHIRPathNode> evaluate(FHIRPathTree tree) throws FHIRPathException {
        return evaluate(tree.getRoot());
    }
    
    public Collection<FHIRPathNode> evaluate(FHIRPathNode node) throws FHIRPathException {
        return evaluate(singleton(node));
    }
    
    public Collection<FHIRPathNode> evaluate(Collection<FHIRPathNode> initialContext) throws FHIRPathException {
        try {
            EvaluatingVisitor visitor = new EvaluatingVisitor(initialContext);
            visitor.pushContext(initialContext);
            Collection<FHIRPathNode> result = visitor.visit(expressionContext);
            visitor.popContext();
            return Collections.unmodifiableCollection(result);
        } catch (Exception e) {
            throw new FHIRPathException("An error occurred while evaluating expression: " + expr, e);
        }
    }
    
    public static FHIRPathEvaluator evaluator(String expr) {
        Objects.requireNonNull(expr);
        ExpressionContext expressionContext = EXPRESSION_CACHE.get(expr);
        if (expressionContext == null) {
            expressionContext = EXPRESSION_CACHE.computeIfAbsent(expr, FHIRPathEvaluator::compile);
        }
        return new FHIRPathEvaluator(expr, expressionContext);
    }
    
    private static ExpressionContext compile(String expr) {
        FHIRPathLexer lexer = new FHIRPathLexer(new ANTLRInputStream(expr));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FHIRPathParser parser = new FHIRPathParser(tokens);
        return parser.expression();
    }
    
    private static class EvaluatingVisitor extends FHIRPathBaseVisitor<Collection<FHIRPathNode>> {        
        private final Collection<FHIRPathNode> initialContext;
        
        private Stack<Collection<FHIRPathNode>> contextStack = new Stack<>();
        private Map<String, Collection<FHIRPathNode>> externalConstantMap = new HashMap<>();
        private int indentLevel = 0;
        
        private EvaluatingVisitor(Collection<FHIRPathNode> initialContext) {
            this.initialContext = initialContext;
            externalConstantMap.put("context", this.initialContext);
            if (isSingleton(this.initialContext)) {
                FHIRPathNode node = getSingleton(this.initialContext);
                if (node.isResourceNode()) {
                    externalConstantMap.put("resource", this.initialContext);
                }
            }
        }
        
        private Collection<FHIRPathNode> as(List<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "as");
            }
            ExpressionContext typeName = arguments.get(0);
            Collection<FHIRPathNode> result = empty();
            Collection<FHIRPathNode> currentContext = getCurrentContext();
            if (isSingleton(currentContext)) {
                String qualifiedIdentifier = typeName.getText();
                FHIRPathType type = FHIRPathType.from(qualifiedIdentifier);
                if (type != null) {
                    FHIRPathNode node = getSingleton(currentContext);
                    if ("System".equals(type.namespace()) && 
                            node.isElementNode() && 
                            node.asElementNode().hasValue()) {
                        node = node.asElementNode().getValue();
                    }
                    if (type.isAssignableFrom(node.type())) {
                        result = singleton(node);
                    }
                }
            }
            return result;
        }

        private Set<String> closure(FHIRPathType type) {
            if ("System".equals(type.namespace())) {
                return Collections.emptySet();
            }
            // compute type name closure
            Set<String> closure = new HashSet<>();
            while (!FHIRPathType.FHIR_ANY.equals(type)) {
                closure.add(type.getName());
                type = type.superType();
            }
            return closure;
        }

        private Collection<FHIRPathNode> exists(List<ExpressionContext> arguments) {
            if (arguments.size() < 0 || arguments.size() > 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "exists");
            }
            Collection<FHIRPathNode> nodes = arguments.isEmpty() ? getCurrentContext() : visit(arguments.get(0));    
            return !nodes.isEmpty() ? SINGLETON_TRUE : SINGLETON_FALSE;
        }

        private Collection<FHIRPathNode> getCurrentContext() {
            if (!contextStack.isEmpty()) {
                return contextStack.peek();
            }
            return empty();
        }
        
        private Collection<FHIRPathNode> is(List<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "is");
            }
            ExpressionContext typeName = arguments.get(0);
            Collection<FHIRPathNode> currentContext = getCurrentContext();
            if (isSingleton(currentContext)) {
                String qualifiedIdentifier = typeName.getText();
                FHIRPathType type = FHIRPathType.from(qualifiedIdentifier);
                if (type != null) {
                    FHIRPathNode node = getSingleton(currentContext);
                    if (type.isAssignableFrom(node.type())) {
                        return SINGLETON_TRUE;
                    }
                }
            }
            return SINGLETON_FALSE;
        }

        private Collection<FHIRPathNode> popContext() {
            if (!contextStack.isEmpty()) {
                return contextStack.pop();
            }
            return null;
        }

        private void pushContext(Collection<FHIRPathNode> context) {
            if (context != null) {
                contextStack.push(context);
            }
        }

        private Collection<FHIRPathNode> select(List<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "select");
            }
            ExpressionContext projection = arguments.get(0);
            Collection<FHIRPathNode> result = new ArrayList<>();
            for (FHIRPathNode node : getCurrentContext()) {
                pushContext(singleton(node));
                result.addAll(visit(projection));
                popContext();
            }
            return result;
        }

        private Collection<FHIRPathNode> trace(List<ExpressionContext> arguments) {
            if (arguments.size() < 1 || arguments.size() > 2) {
                throw unexpectedNumberOfArguments(arguments.size(), "trace");
            }
            String name = getString(visit(arguments.get(0)));            
            Collection<FHIRPathNode> currentContext = getCurrentContext();
            Collection<FHIRPathNode> nodes = arguments.size() == 1 ? currentContext : visit(arguments.get(1));
            if (!nodes.isEmpty()) {
                // TODO: add to log
                System.out.println(name + ": " + nodes);
            }
            return currentContext;
        }

        private IllegalArgumentException unexpectedNumberOfArguments(int arity, String functionName) {
            return new IllegalArgumentException(String.format("Unexpected number of arguments: %d for function: '%s'", arity, functionName));
        }

        private Collection<FHIRPathNode> where(List<ExpressionContext> arguments) {
            if (arguments.size() != 1) {
                throw unexpectedNumberOfArguments(arguments.size(), "where");
            }
            ExpressionContext criteria = arguments.get(0);
            Collection<FHIRPathNode> result = new ArrayList<>();
            for (FHIRPathNode node : getCurrentContext()) {
                pushContext(singleton(node));
                if (getBoolean(visit(criteria))) {
                    result.add(node);
                }
                popContext();
            }
            return result;
        }

        /**
         * expression '[' expression ']'
         */
        @Override
        public Collection<FHIRPathNode> visitIndexerExpression(FHIRPathParser.IndexerExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> result = empty();
            
            Collection<FHIRPathNode> nodes = visit(ctx.expression(0));
            
            List<?> list = (nodes instanceof List) ? (List<?>) nodes : new ArrayList<>(nodes);
            int index = getInteger(visit(ctx.expression(1)));
            
            if (index >= 0 && index < list.size()) {
                result = singleton((FHIRPathNode) list.get(index));
            }
                                    
            indentLevel--;
            return result;
        }
        
        /**
         * ('+' | '-') expression
         */
        @Override
        public Collection<FHIRPathNode> visitPolarityExpression(FHIRPathParser.PolarityExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> nodes = visit(ctx.expression());
            
            if (!isSingleton(nodes)) {
                indentLevel--;
                return empty();
            }
            
            Collection<FHIRPathNode> result = empty();
            
            FHIRPathPrimitiveValue value = getPrimitiveValue(nodes);
            String polarity = ctx.getChild(0).getText();
            
            if (value.isNumberValue()) {
                switch (polarity) {
                case "+":
                    result = singleton(value.asNumberValue().plus());
                    break;
                case "-":
                    result = singleton(value.asNumberValue().negate());
                    break;
                }
            }
            
            indentLevel--;
            return result;
        }
        
        /**
         * expression ('+' | '-' | '&') expression
         */
        @Override
        public Collection<FHIRPathNode> visitAdditiveExpression(FHIRPathParser.AdditiveExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));

            Collection<FHIRPathNode> result = empty();
            
            String operator = ctx.getChild(1).getText();
            
            if (hasPrimitiveValue(left) && hasPrimitiveValue(right)) {
                FHIRPathPrimitiveValue leftValue = getPrimitiveValue(left);
                FHIRPathPrimitiveValue rightValue = getPrimitiveValue(right);
                
                if (leftValue.isNumberValue() && rightValue.isNumberValue()) {            
                    switch (operator) {
                    case "+":
                        result = singleton(leftValue.asNumberValue().add(rightValue.asNumberValue()));
                        break;
                    case "-":
                        result = singleton(leftValue.asNumberValue().subtract(rightValue.asNumberValue()));
                        break;
                    }
                } else if (leftValue.isStringValue() && rightValue.isStringValue() && ("+".equals(operator) || "&".equals(operator))) {
                    // concatenation
                    result = singleton(leftValue.asStringValue().concat(rightValue.asStringValue()));
                }
            } else if ("&".equals(operator)) {
                // concatenation where an empty collection is treated as an empty string
                if (hasPrimitiveValue(left) && right.isEmpty()) {
                    FHIRPathPrimitiveValue leftValue = getPrimitiveValue(left);
                    if (leftValue.isStringValue()) {
                        result = singleton(leftValue.asStringValue().concat(EMPTY_STRING));
                    }
                } else if (left.isEmpty() && hasPrimitiveValue(right)) {
                    FHIRPathPrimitiveValue rightValue = getPrimitiveValue(right);
                    if (rightValue.isStringValue()) {
                        result = singleton(EMPTY_STRING.concat(rightValue.asStringValue()));
                    }
                } else if (left.isEmpty() && right.isEmpty()) {
                    result = singleton(EMPTY_STRING);
                }
            }
                                    
            indentLevel--;
            return result;
        }
    
        /**
         * expression ('*' | '/' | 'div' | 'mod') expression
         */
        @Override
        public Collection<FHIRPathNode> visitMultiplicativeExpression(FHIRPathParser.MultiplicativeExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));
            
            if (!hasPrimitiveValue(left) || !hasPrimitiveValue(right)) {
                indentLevel--;
                return empty();
            }
            
            Collection<FHIRPathNode> result = empty();
            
            FHIRPathPrimitiveValue leftValue = getPrimitiveValue(left);
            FHIRPathPrimitiveValue rightValue = getPrimitiveValue(right);
            
            String operator = ctx.getChild(1).getText();

            if (leftValue.isNumberValue() && rightValue.isNumberValue()) {
                switch (operator) {
                case "*":
                    result = singleton(leftValue.asNumberValue().multiply(rightValue.asNumberValue()));
                    break;
                case "/":
                    result = singleton(leftValue.asNumberValue().divide(rightValue.asNumberValue()));
                    break;
                case "div":
                    result = singleton(leftValue.asNumberValue().div(rightValue.asNumberValue()));
                    break;
                case "mod":
                    result = singleton(leftValue.asNumberValue().mod(rightValue.asNumberValue()));
                    break;
                }
            }
            
            indentLevel--;
            return result;
        }
    
        /**
         * expression '|' expression
         */
        @Override
        public Collection<FHIRPathNode> visitUnionExpression(FHIRPathParser.UnionExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));
            
            Set<FHIRPathNode> union = new HashSet<>(left);
            union.addAll(right);
            
            indentLevel--;
//          return union;
            return new ArrayList<>(union);
        }
    
        /**
         * expression ('or' | 'xor') expression
         */
        @Override
        public Collection<FHIRPathNode> visitOrExpression(FHIRPathParser.OrExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));
            
            if (!hasPrimitiveValue(left) || !hasPrimitiveValue(right)) {
                indentLevel--;
                return SINGLETON_FALSE;
            }
            
            Collection<FHIRPathNode> result = SINGLETON_FALSE;
            
            FHIRPathPrimitiveValue leftValue = getPrimitiveValue(left);
            FHIRPathPrimitiveValue rightValue = getPrimitiveValue(right);
            
            String operator = ctx.getChild(1).getText();
            
            if (leftValue.isBooleanValue() && rightValue.isBooleanValue()) {
                switch (operator) {
                case "or":
                    if (leftValue.asBooleanValue().or(rightValue.asBooleanValue()).isTrue()) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                case "xor":
                    if (leftValue.asBooleanValue().xor(rightValue.asBooleanValue()).isTrue()) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                }
            }
            
            indentLevel--;
            return result;
        }
    
        /**
         * expression 'and' expression
         */
        @Override
        public Collection<FHIRPathNode> visitAndExpression(FHIRPathParser.AndExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));
            
            if (!hasPrimitiveValue(left) || !hasPrimitiveValue(right)) {
                indentLevel--;
                return SINGLETON_FALSE;
            }
            
            Collection<FHIRPathNode> result = SINGLETON_FALSE;
            
            FHIRPathPrimitiveValue leftValue = getPrimitiveValue(left);
            FHIRPathPrimitiveValue rightValue = getPrimitiveValue(right);
            
            if (leftValue.isBooleanValue() && rightValue.isBooleanValue()) {
                if (leftValue.asBooleanValue().and(rightValue.asBooleanValue()).isTrue()) {
                    result = SINGLETON_TRUE;
                }
            }
            
            indentLevel--;
            return result;
        }
    
        /**
         * expression ('in' | 'contains') expression
         */
        @Override
        public Collection<FHIRPathNode> visitMembershipExpression(FHIRPathParser.MembershipExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> result = SINGLETON_FALSE;
            
            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));
            
            String operator = ctx.getChild(1).getText();

            switch (operator) {
            case "in":
                if (right.containsAll(left)) {
                    result = SINGLETON_TRUE;
                }
                break;
            case "contains":
                if (left.containsAll(right)) {
                    result = SINGLETON_TRUE;
                }
                break;
            }
            
            indentLevel--;
            return result;
        }
    
        /**
         * expression ('<=' | '<' | '>' | '>=') expression
         */
        @Override
        public Collection<FHIRPathNode> visitInequalityExpression(FHIRPathParser.InequalityExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));
            
            if (!hasPrimitiveValue(left) || !hasPrimitiveValue(right)) {
                indentLevel--;
                return SINGLETON_FALSE;
            }
            
            Collection<FHIRPathNode> result = SINGLETON_FALSE;
            
            FHIRPathPrimitiveValue leftValue = getPrimitiveValue(left);
            FHIRPathPrimitiveValue rightValue = getPrimitiveValue(right);
            
            String operator = ctx.getChild(1).getText();

            if (leftValue.isNumberValue() && rightValue.isNumberValue()) {
                switch (operator) {
                case "<=":
                    if (leftValue.asNumberValue().lessThanOrEqual(rightValue.asNumberValue())) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                case "<":
                    if (leftValue.asNumberValue().lessThan(rightValue.asNumberValue())) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                case ">":
                    if (leftValue.asNumberValue().greaterThan(rightValue.asNumberValue())) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                case ">=":
                    if (leftValue.asNumberValue().greaterThanOrEqual(rightValue.asNumberValue())) {
                        result = SINGLETON_TRUE;
                    }
                    break;
                }
            }

            indentLevel--;
            return result;
        }
    
        /**
         * expression '.' invocation
         */
        @Override
        public Collection<FHIRPathNode> visitInvocationExpression(FHIRPathParser.InvocationExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            pushContext(visit(ctx.expression()));
            Collection<FHIRPathNode> result = visit(ctx.invocation());
            popContext();
            
            indentLevel--;
            return result;
        }
    
        /**
         * expression ('=' | '~' | '!=' | '!~') expression
         */
        @Override
        public Collection<FHIRPathNode> visitEqualityExpression(FHIRPathParser.EqualityExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> result = SINGLETON_FALSE;

            Collection<FHIRPathNode> left = visit(ctx.expression(0));
            Collection<FHIRPathNode> right = visit(ctx.expression(1));
            
            String operator = ctx.getChild(1).getText();
            
            // TODO: "equals" and "equivalent" have different semantics
            
            switch (operator) {
            case "=":
            case "~":
                if (left.equals(right)) {
                    result = SINGLETON_TRUE;
                }
                break;
            case "!=":
            case "!~":
                if (!left.equals(right)) {
                    result = SINGLETON_TRUE;
                }
                break;
            }

            indentLevel--;
            return result;
        }
    
        /**
         * expression 'implies' expression
         */
        @Override
        public Collection<FHIRPathNode> visitImpliesExpression(FHIRPathParser.ImpliesExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> result = SINGLETON_FALSE;
            
            FHIRPathPrimitiveValue left = getPrimitiveValue(visit(ctx.expression(0)));
            FHIRPathPrimitiveValue right = getPrimitiveValue(visit(ctx.expression(1)));
            
            if (left.isBooleanValue() && right.isBooleanValue() && left.asBooleanValue().implies(right.asBooleanValue()).isTrue()) {
                return SINGLETON_TRUE;
            }
            
            indentLevel--;
            return result;
        }
        
        /**
         * term
         */
        @Override
        public Collection<FHIRPathNode> visitTermExpression(FHIRPathParser.TermExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * expression ('is' | 'as') typeSpecifier
         */
        @Override
        public Collection<FHIRPathNode> visitTypeExpression(FHIRPathParser.TypeExpressionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> nodes = visit(ctx.expression());
            String operator = ctx.getChild(1).getText();
            
            Collection<FHIRPathNode> result = "is".equals(operator) ? SINGLETON_FALSE : empty();
                        
            if (isSingleton(nodes)) {
                String qualifiedIdentifier = getString(visit(ctx.typeSpecifier()));
                FHIRPathType type = FHIRPathType.from(qualifiedIdentifier);
                if (type != null) {
                    FHIRPathNode node = getSingleton(nodes);
                    switch (operator) {
                    case "is":
                        if (type.isAssignableFrom(node.type())) {
                            result = SINGLETON_TRUE;
                        }
                        break;
                    case "as":
                        if (type.isAssignableFrom(node.type())) {
                            result = singleton(node);
                        }
                        break;
                    }
                }
            }
            
            indentLevel--;
            return result;
        }

        /**
         * invocation
         */
        @Override
        public Collection<FHIRPathNode> visitInvocationTerm(FHIRPathParser.InvocationTermContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * literal
         */
        @Override
        public Collection<FHIRPathNode> visitLiteralTerm(FHIRPathParser.LiteralTermContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * externalConstant
         */
        @Override
        public Collection<FHIRPathNode> visitExternalConstantTerm(FHIRPathParser.ExternalConstantTermContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * '(' expression ')'
         */
        @Override
        public Collection<FHIRPathNode> visitParenthesizedTerm(FHIRPathParser.ParenthesizedTermContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visit(ctx.expression());
            indentLevel--;
            return result;
        }
    
        /**
         * '{' '}'
         */
        @Override
        public Collection<FHIRPathNode> visitNullLiteral(FHIRPathParser.NullLiteralContext ctx) {
            debug(ctx);
            return empty();
        }
    
        /**
         * ('true' | 'false')
         */
        @Override
        public Collection<FHIRPathNode> visitBooleanLiteral(FHIRPathParser.BooleanLiteralContext ctx) {
            debug(ctx);
            Boolean _boolean = Boolean.valueOf(ctx.getText());
            return _boolean ? SINGLETON_TRUE : SINGLETON_FALSE;
        }
    
        /**
         * '\'' (ESC | .)*? '\''
         */
        @Override
        public Collection<FHIRPathNode> visitStringLiteral(FHIRPathParser.StringLiteralContext ctx) {
            debug(ctx);
            String text = ctx.getText();
            return singleton(stringValue(text.substring(1, text.length() - 1)));
        }
    
        /**
         * [0-9]+('.' [0-9]+)?
         */
        @Override
        public Collection<FHIRPathNode> visitNumberLiteral(FHIRPathParser.NumberLiteralContext ctx) {
            debug(ctx);
            BigDecimal decimal = new BigDecimal(ctx.getText());
            try {
                Integer integer = decimal.intValueExact();
                return singleton(integerValue(integer));
            } catch (ArithmeticException e) {
                return singleton(decimalValue(decimal));
            }
        }
    
        /**
         * '@'
         *  [0-9][0-9][0-9][0-9] // year
         *  (
         *      '-'[0-9][0-9] // month
         *      (
         *          '-'[0-9][0-9] // day
         *          (
         *              'T' TIMEFORMAT
         *          )?
         *       )?
         *   )?
         *   'Z'? // UTC specifier
         */
        @Override
        public Collection<FHIRPathNode> visitDateTimeLiteral(FHIRPathParser.DateTimeLiteralContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * [0-9][0-9] (':'[0-9][0-9] (':'[0-9][0-9] ('.'[0-9]+)?)?)?
         * ('Z' | ('+' | '-') [0-9][0-9]':'[0-9][0-9])? // timezone
         */
        @Override
        public Collection<FHIRPathNode> visitTimeLiteral(FHIRPathParser.TimeLiteralContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * NUMBER unit?
         */
        @Override
        public Collection<FHIRPathNode> visitQuantityLiteral(FHIRPathParser.QuantityLiteralContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * '%' identifier
         */
        @Override
        public Collection<FHIRPathNode> visitExternalConstant(FHIRPathParser.ExternalConstantContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> result = empty();
            
            String identifier = getString(visit(ctx.identifier()));
            if (externalConstantMap.containsKey(identifier)) {
                result = externalConstantMap.get(identifier);
            }

            indentLevel--;
            return result;
        }
    
        /**
         * identifier
         */
        @Override
        public Collection<FHIRPathNode> visitMemberInvocation(FHIRPathParser.MemberInvocationContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> currentContext = getCurrentContext();
            String identifier = getString(visit(ctx.identifier()));
            
            if (isSingleton(currentContext)) {
                FHIRPathNode node = getSingleton(currentContext);
                if (closure(node.type()).contains(identifier)) {
                    indentLevel--;
                    return currentContext;
                }
            }
            
            Collection<FHIRPathNode> result = currentContext.stream()
                    .flatMap(node -> node.children().stream())
                    .filter(node -> identifier.equals(node.name()))
                    .collect(Collectors.toList());
            
            indentLevel--;
            return result;
        }
        
        /**
         * function
         */
        @Override
        public Collection<FHIRPathNode> visitFunctionInvocation(FHIRPathParser.FunctionInvocationContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * '$this'
         */
        @Override
        public Collection<FHIRPathNode> visitThisInvocation(FHIRPathParser.ThisInvocationContext ctx) {
            debug(ctx);
            return getCurrentContext();
        }
    
        /**
         * '$index'
         */
        @Override
        public Collection<FHIRPathNode> visitIndexInvocation(FHIRPathParser.IndexInvocationContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        /**
         * '$total'
         */
        @Override
        public Collection<FHIRPathNode> visitTotalInvocation(FHIRPathParser.TotalInvocationContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
        
        /**
         * identifier '(' paramList? ')'
         */
        @Override
        public Collection<FHIRPathNode> visitFunction(FHIRPathParser.FunctionContext ctx) {
            debug(ctx);
            indentLevel++;
            
            Collection<FHIRPathNode> result = empty();
            
            String functionName = getString(visit(ctx.identifier()));

            List<ExpressionContext> arguments = new ArrayList<ExpressionContext>();
            ParamListContext paramList = ctx.paramList();
            if (paramList != null) {
                arguments.addAll(ctx.paramList().expression());
            }
                        
            Collection<FHIRPathNode> currentContext = getCurrentContext();
            
            switch (functionName) {
            case "as":
                result = as(arguments);
                break;   
            case "exists":
                result = exists(arguments);
                break;
            case "is":
                result = is(arguments);
                break;
            case "select":
                result = select(arguments);
                break;
            case "trace":
                result = trace(arguments);
                break;
            case "where":
                result = where(arguments);
                break;
            default:
                FHIRPathFunction function = FHIRPathFunction.registry().getFunction(functionName);
                if (function == null) {
                    throw new IllegalArgumentException("Function: '" + functionName + "' not found");
                }
                // evaluate arguments: ExpressionContext -> Collection<FHIRPathNode>
                List<Collection<FHIRPathNode>> args = arguments.stream().map(expressionContext -> visit(expressionContext)).collect(Collectors.toList());
                if (args.size() < function.getMinArity() && args.size() > function.getMaxArity()) {
                    throw unexpectedNumberOfArguments(args.size(), functionName);
                }
                result = function.apply(currentContext, args);
                break;
            }
                        
            indentLevel--;
            return result;
        }
        
        @Override
        public Collection<FHIRPathNode> visitParamList(FHIRPathParser.ParamListContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        @Override
        public Collection<FHIRPathNode> visitQuantity(FHIRPathParser.QuantityContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        @Override
        public Collection<FHIRPathNode> visitUnit(FHIRPathParser.UnitContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        @Override
        public Collection<FHIRPathNode> visitDateTimePrecision(FHIRPathParser.DateTimePrecisionContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        @Override
        public Collection<FHIRPathNode> visitPluralDateTimePrecision(FHIRPathParser.PluralDateTimePrecisionContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        @Override
        public Collection<FHIRPathNode> visitTypeSpecifier(FHIRPathParser.TypeSpecifierContext ctx) {
            debug(ctx);
            indentLevel++;
            Collection<FHIRPathNode> result = visitChildren(ctx);
            indentLevel--;
            return result;
        }
    
        @Override
        public Collection<FHIRPathNode> visitQualifiedIdentifier(FHIRPathParser.QualifiedIdentifierContext ctx) {
            debug(ctx);
            return singleton(stringValue(ctx.getText()));
        }
    
        @Override
        public Collection<FHIRPathNode> visitIdentifier(FHIRPathParser.IdentifierContext ctx) {
            debug(ctx);
            return singleton(stringValue(ctx.getChild(0).getText()));
        }
    
        private String indent() {
            StringBuilder builder = new StringBuilder();
            for (int i = 0;i < indentLevel; i++) {
                builder.append("    ");
            }
            return builder.toString();
        }
    
        private void debug(ParseTree ctx) {
            if (DEBUG) {
                System.out.println(indent() + ctx.getClass().getSimpleName() + ": " + ctx.getText() + ", childCount: " + ctx.getChildCount());
            }
        }
    }
    
    public static void main(String[] args) throws Exception {
        FHIRPathEvaluator.DEBUG = true;
        Collection<FHIRPathNode> result = FHIRPathEvaluator.evaluator("'Hello'.is(System.hamburger)").evaluate();
        System.out.println(result);        
    }
}
