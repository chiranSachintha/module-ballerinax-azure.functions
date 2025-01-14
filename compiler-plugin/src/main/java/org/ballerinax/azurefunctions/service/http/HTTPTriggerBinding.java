/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ballerinax.azurefunctions.service.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.IdentifierToken;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.ParameterNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.RequiredParameterNode;
import io.ballerina.compiler.syntax.tree.ResourcePathParameterNode;
import io.ballerina.compiler.syntax.tree.ReturnTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import org.ballerinax.azurefunctions.Constants;
import org.ballerinax.azurefunctions.FunctionContext;
import org.ballerinax.azurefunctions.Util;
import org.ballerinax.azurefunctions.service.Binding;
import org.ballerinax.azurefunctions.service.InputBindingBuilder;
import org.ballerinax.azurefunctions.service.OutputBindingBuilder;
import org.ballerinax.azurefunctions.service.TriggerBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents a HTTP Trigger binding in functions.json.
 *
 * @since 2.0.0
 */
public class HTTPTriggerBinding extends TriggerBinding {

    private String path;
    private String authLevel = "anonymous";
    private String methods;

    public HTTPTriggerBinding(ServiceDeclarationNode serviceDeclarationNode, SemanticModel semanticModel) {
        super("httpTrigger");
        this.setVarName("httpPayload");
        this.serviceDeclarationNode = serviceDeclarationNode;
        this.semanticModel = semanticModel;
    }

    @Override
    public List<FunctionContext> getBindings() {
        Optional<AnnotationNode> httpTriggerAnnot =
                getListenerAnnotation(this.serviceDeclarationNode, Constants.ANNOTATION_HTTP_TRIGGER);
        String servicePath = Util.resourcePathToString(serviceDeclarationNode.absoluteResourcePath());
        servicePath = servicePath.replace("\\", "");
        List<FunctionContext> functionContexts = new ArrayList<>();
        NodeList<Node> members = this.serviceDeclarationNode.members();
        for (Node node : members) {
            HTTPTriggerBinding httpTriggerBinding =
                    new HTTPTriggerBinding(this.serviceDeclarationNode, this.semanticModel);
            httpTriggerAnnot.ifPresent(trigger -> getAnnotation(httpTriggerBinding, trigger));
            List<Binding> bindings = new ArrayList<>();
            if (SyntaxKind.RESOURCE_ACCESSOR_DEFINITION != node.kind()) {
                continue;
            }
            FunctionDefinitionNode functionDefinitionNode = (FunctionDefinitionNode) node;
            String method = functionDefinitionNode.functionName().text();
            httpTriggerBinding.setMethods(method);
            StringBuilder resourcePath = new StringBuilder();
            resourcePath.append(servicePath);
            for (Node pathBlock : functionDefinitionNode.relativeResourcePath()) {
                if (pathBlock.kind() == SyntaxKind.IDENTIFIER_TOKEN) {
                    String specialCharReplacedPathBlock = (((IdentifierToken) pathBlock).text()).replace("\\", "");
                    resourcePath.append("/").append(specialCharReplacedPathBlock);
                    continue;
                }
                if (pathBlock.kind() == SyntaxKind.RESOURCE_PATH_SEGMENT_PARAM) {
                    ResourcePathParameterNode pathParamNode = (ResourcePathParameterNode) pathBlock;
                    //TODO Handle optional
                    resourcePath.append("/" + "{").append(pathParamNode.paramName().get().text()).append("}");
                }
                if (pathBlock.kind() == SyntaxKind.RESOURCE_PATH_REST_PARAM) {
                    ResourcePathParameterNode pathParamNode = (ResourcePathParameterNode) pathBlock;
                    if (pathParamNode.paramName().isPresent()) {
                        resourcePath.append("/" + "{**").append(pathParamNode.paramName().get().text()).append("}");
                    }
                }
            }
            httpTriggerBinding.setPath(getFunctionPath(resourcePath.toString()));
            bindings.add(httpTriggerBinding);
            String variableName;
            SeparatedNodeList<ParameterNode> parameters = functionDefinitionNode.functionSignature().parameters();
            for (ParameterNode parameterNode : parameters) {
                if (parameterNode.kind() != SyntaxKind.REQUIRED_PARAM) {
                    continue;
                }
                RequiredParameterNode reqParam = (RequiredParameterNode) parameterNode;
                if (reqParam.paramName().isEmpty()) {
                    continue;
                }
                variableName = reqParam.paramName().get().text();
                InputBindingBuilder inputBuilder = new InputBindingBuilder();
                Optional<Binding> inputBinding = inputBuilder.getInputBinding(reqParam.annotations(), variableName);
                inputBinding.ifPresent(bindings::add);
            }
            Optional<ReturnTypeDescriptorNode> returnTypeDescriptor =
                    functionDefinitionNode.functionSignature().returnTypeDesc();
            if (returnTypeDescriptor.isEmpty()) {
                bindings.add(new HTTPOutputBinding(null, 0));
            } else {
                ReturnTypeDescriptorNode returnTypeNode = returnTypeDescriptor.get();
                OutputBindingBuilder outputBuilder = new OutputBindingBuilder();
                List<Binding> returnBinding = outputBuilder.getOutputBinding(returnTypeNode);
                if (returnBinding.isEmpty()) {
                    bindings.add(new HTTPOutputBinding(null, 0));
                } else {
                    bindings.addAll(returnBinding); //TODO handle in code analyzer
                }
            }
            Optional<String> functionName = getFunctionNameFromAnnotation(functionDefinitionNode);
            functionContexts.add(new FunctionContext(functionName.get(), bindings));
        }
        return functionContexts;
    }

    private String getFunctionPath(String resourcePath) {
        if (resourcePath.startsWith("/")) {
            return resourcePath.substring(1);
        } else {
            return resourcePath;
        }
    }

    private void getAnnotation(HTTPTriggerBinding triggerBinding, AnnotationNode queueTrigger) {
        SeparatedNodeList<MappingFieldNode> fields = queueTrigger.annotValue().orElseThrow().fields();
        for (MappingFieldNode fieldNode : fields) {
            extractValueFromAnnotation(triggerBinding, (SpecificFieldNode) fieldNode);
        }
    }

    private void extractValueFromAnnotation(HTTPTriggerBinding triggerBinding, SpecificFieldNode fieldNode) {
        String text = ((IdentifierToken) fieldNode.fieldName()).text();
        Optional<String> value = Util.extractValueFromAnnotationField(fieldNode);
        switch (text) {
            case "authLevel":
                value.ifPresent(triggerBinding::setAuthLevel);
                break;
        }
    }

    public Optional<String> getFunctionNameFromAnnotation(FunctionDefinitionNode functionDefinitionNode) {
        MetadataNode metadataNode = functionDefinitionNode.metadata().orElseThrow();
        NodeList<AnnotationNode> annotations = metadataNode.annotations();
        for (AnnotationNode annotationNode : annotations) {
            Node ref = annotationNode.annotReference();
            if (ref.kind() != SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                continue;
            }
            QualifiedNameReferenceNode qualifiedRef = (QualifiedNameReferenceNode) ref;
            if (!qualifiedRef.identifier().text().equals(Constants.FUNCTION_ANNOTATION)) {
                continue;
            }
            Optional<MappingConstructorExpressionNode> val =
                    annotationNode.annotValue();
            if (val.isEmpty()) {
                continue;
            }
            SeparatedNodeList<MappingFieldNode> fields = val.get().fields();
            for (MappingFieldNode field : fields) {
                if (field.kind() != SyntaxKind.SPECIFIC_FIELD) {
                    continue;
                }
                SpecificFieldNode specificFieldNode = (SpecificFieldNode) field;
                Node fieldNameNode = specificFieldNode.fieldName();
                if (fieldNameNode.kind() != SyntaxKind.IDENTIFIER_TOKEN) {
                    continue;
                }
                String fieldName = ((IdentifierToken) fieldNameNode).text();
                if (!fieldName.equals("name")) {
                    continue;
                }
                Optional<ExpressionNode> expressionNode = specificFieldNode.valueExpr();
                if (expressionNode.isEmpty()) {
                    continue;
                }
                ExpressionNode value = expressionNode.get();
                if (value.kind() != SyntaxKind.STRING_LITERAL) {
                    continue;
                }
                BasicLiteralNode literalNode = (BasicLiteralNode) value;
                String functionName = literalNode.literalToken().text();
                return Optional.of(functionName.substring(1, functionName.length() - 1));
            }
        }
        return Optional.empty();
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setAuthLevel(String authLevel) {
        this.authLevel = authLevel;
    }

    public void setMethods(String methods) {
        this.methods = methods;
    }

    public String getPath() {
        return path;
    }

    public String getAuthLevel() {
        return authLevel;
    }

    public String getMethods() {
        return methods;
    }

    @Override
    public JsonObject getJsonObject() {
        JsonObject inputTrigger = new JsonObject();
        inputTrigger.addProperty("type", this.getTriggerType());
        inputTrigger.addProperty("authLevel", this.getAuthLevel());
        inputTrigger.add("methods", generateMethods());
        inputTrigger.addProperty("direction", this.getDirection());
        inputTrigger.addProperty("name", this.getVarName());
        inputTrigger.addProperty("route", this.getPath());
        return inputTrigger;
    }

    private JsonArray generateMethods() {
        JsonArray methods = new JsonArray();
        if (this.methods.equals("default")) {
            methods.add("DELETE");
            methods.add("GET");
            methods.add("HEAD");
            methods.add("OPTIONS");
            methods.add("POST");
            methods.add("PUT");
        } else {
            methods.add(this.getMethods());
        }
        return methods;
    }
}
