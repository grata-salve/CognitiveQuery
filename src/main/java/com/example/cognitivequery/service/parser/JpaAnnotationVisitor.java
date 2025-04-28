package com.example.cognitivequery.service.parser;


import com.example.cognitivequery.model.ir.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedVoidType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;


@Slf4j
public class JpaAnnotationVisitor extends VoidVisitorAdapter<Object> {

    public enum ProcessingPass {FIRST_PASS, SECOND_PASS}

    private final Map<String, MappedSuperclassInfo> mappedSuperclasses;
    private final List<EmbeddableInfo> embeddables;
    private final SchemaInfo schemaInfo;
    private static final Map<String, String> JAVA_TO_SQL_TYPE_MAP = createJavaToSqlTypeMap();

    public JpaAnnotationVisitor(Map<String, MappedSuperclassInfo> mappedSuperclasses, List<EmbeddableInfo> embeddables) {
        this(mappedSuperclasses, embeddables, null);
    }

    public JpaAnnotationVisitor(Map<String, MappedSuperclassInfo> mappedSuperclasses, List<EmbeddableInfo> embeddables, SchemaInfo schemaInfo) {
        this.mappedSuperclasses = mappedSuperclasses;
        this.embeddables = Objects.requireNonNullElseGet(embeddables, ArrayList::new);
        this.schemaInfo = schemaInfo;
    }

    // --- VISITOR METHODS
    @Override
    public void visit(CompilationUnit cu, Object arg) {
        super.visit(cu, arg);
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration cl, Object arg) {
        super.visit(cl, arg);
        if (cl.isInterface() || !(cl.isAnnotationPresent("Entity") || cl.isAnnotationPresent("MappedSuperclass") || cl.isAnnotationPresent("Embeddable"))) {
            return;
        }
        String className = cl.getFullyQualifiedName().orElse(cl.getNameAsString());
        ProcessingPass currentPass = (arg instanceof ProcessingPass) ? (ProcessingPass) arg : null;
        if (currentPass == null) {
            log.trace("Visitor argument is not ProcessingPass, skipping class {}", className);
            return;
        }
        try {
            switch (currentPass) {
                case FIRST_PASS:
                    if (cl.isAnnotationPresent("MappedSuperclass")) {
                        MappedSuperclassInfo superclassInfo = parseMappedSuperclass(cl);
                        if (superclassInfo != null) {
                            mappedSuperclasses.put(className, superclassInfo);
                            log.debug("Parsed MappedSuperclass: {}", className);
                        }
                    } else if (cl.isAnnotationPresent("Embeddable")) {
                        EmbeddableInfo embeddableInfo = parseEmbeddable(cl);
                        if (embeddableInfo != null) {
                            if (embeddables.stream().noneMatch(e -> e.getJavaClassName().equals(className))) {
                                embeddables.add(embeddableInfo);
                                log.debug("Parsed Embeddable: {}", className);
                            }
                        }
                    }
                    break;
                case SECOND_PASS:
                    if (schemaInfo != null && cl.isAnnotationPresent("Entity")) {
                        EntityInfo entityInfo = parseEntity(cl);
                        if (entityInfo != null) {
                            if (schemaInfo.getEntities().stream().noneMatch(e -> e.getJavaClassName().equals(className))) {
                                schemaInfo.getEntities().add(entityInfo);
                                log.debug("Parsed Entity: {}", className);
                            }
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            log.error("Error processing class declaration {} during pass {}: {}", className, currentPass, e.getMessage(), e);
        }
    }

    // --- PARSING LOGIC
    private MappedSuperclassInfo parseMappedSuperclass(ClassOrInterfaceDeclaration cl) {
        String className = cl.getFullyQualifiedName().orElse(cl.getNameAsString());
        log.trace("Parsing MappedSuperclass details for: {}", className);
        MappedSuperclassInfo info = new MappedSuperclassInfo();
        info.setJavaClassName(className);
        addInheritedMappedSuperclassFields(cl, info);
        for (FieldDeclaration field : cl.getFields()) {
            parseField(field, info.getColumns(), info.getRelationships(), null, info, false, false);
        }
        return info;
    }

    private EmbeddableInfo parseEmbeddable(ClassOrInterfaceDeclaration cl) {
        String className = cl.getFullyQualifiedName().orElse(cl.getNameAsString());
        log.trace("Parsing Embeddable details for: {}", className);
        EmbeddableInfo info = new EmbeddableInfo();
        info.setJavaClassName(className);
        for (FieldDeclaration field : cl.getFields()) {
            parseField(field, info.getFields(), null, null, null, false, false);
        }
        return info;
    }

    private EntityInfo parseEntity(ClassOrInterfaceDeclaration cl) {
        String className = cl.getFullyQualifiedName().orElse(cl.getNameAsString());
        log.trace("Parsing Entity details for: {}", className);
        EntityInfo info = new EntityInfo();
        info.setJavaClassName(className);
        info.setTableName(extractAnnotationAttribute(cl.getAnnotationByName("Table").orElse(null), "name").orElse(toSnakeCase(cl.getNameAsString())));
        cl.getExtendedTypes().stream().findFirst().ifPresent(extendedType -> {
            try {
                ResolvedType resolved = extendedType.resolve();
                if (resolved.isReferenceType()) {
                    String superClassName = resolved.asReferenceType().getQualifiedName();
                    if (mappedSuperclasses.containsKey(superClassName)) {
                        log.trace("Entity {} extends MappedSuperclass {}", className, superClassName);
                        info.setMappedSuperclass(superClassName);
                        addInheritedMembers(info, superClassName, cl);
                    } else {
                        log.trace("Entity {} extends {} which is not a known MappedSuperclass", className, superClassName);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not resolve or process superclass {} for Entity {}: {}", extendedType.getNameAsString(), className, e.getMessage());
            }
        });
        log.trace("Parsing direct fields for Entity {}", className);
        for (FieldDeclaration field : cl.getFields()) {
            parseField(field, info.getColumns(), info.getRelationships(), info, null, false, false);
        }
        return info;
    }

    private void addInheritedMappedSuperclassFields(ClassOrInterfaceDeclaration cl, MappedSuperclassInfo currentInfo) {
        cl.getExtendedTypes().stream().findFirst().ifPresent(extendedType -> {
            try {
                ResolvedType resolved = extendedType.resolve();
                if (resolved.isReferenceType()) {
                    String superClassName = resolved.asReferenceType().getQualifiedName();
                    MappedSuperclassInfo parentInfo = mappedSuperclasses.get(superClassName);
                    if (parentInfo != null) {
                        log.trace("Adding inherited fields from already parsed {} to {}", superClassName, currentInfo.getJavaClassName());
                        Optional<Node> parentNodeOpt = resolved.asReferenceType().getTypeDeclaration().filter(ResolvedReferenceTypeDeclaration::isClass).flatMap(td -> td.asClass().toAst());
                        if (parentNodeOpt.isPresent() && parentNodeOpt.get() instanceof ClassOrInterfaceDeclaration parentCl) {
                            addInheritedMappedSuperclassFields(parentCl, currentInfo);
                        } else {
                            log.warn("Could not get AST ClassOrInterfaceDeclaration for parent MappedSuperclass {} to continue recursive inheritance", superClassName);
                        }
                        parentInfo.getColumns().forEach(col -> addIfNotPresent(currentInfo.getColumns(), deepCopyColumnInfo(col), true, superClassName));
                        parentInfo.getRelationships().forEach(rel -> addIfNotPresent(currentInfo.getRelationships(), deepCopyRelationshipInfo(rel), true, superClassName));
                    } else {
                        log.trace("Parent class {} of MappedSuperclass {} is not itself a known MappedSuperclass", superClassName, currentInfo.getJavaClassName());
                    }
                }
            } catch (Exception e) {
                log.warn("Error resolving or processing superclass {} for {}: {}", extendedType.getNameAsString(), currentInfo.getJavaClassName(), e.getMessage());
            }
        });
    }

    private void addInheritedMembers(EntityInfo entityInfo, String superClassName, ClassOrInterfaceDeclaration entityClassDecl) {
        MappedSuperclassInfo parentInfo = mappedSuperclasses.get(superClassName);
        if (parentInfo == null) return;
        Map<String, String> columnOverrides = getAttributeOverrides(entityClassDecl.getAnnotationByName("AttributeOverrides").orElse(null));
        parentInfo.getColumns().forEach(col -> {
            ColumnInfo inheritedCol = deepCopyColumnInfo(col);
            String overrideKey = inheritedCol.getFieldName();
            if (columnOverrides.containsKey(overrideKey)) {
                inheritedCol.setColumnName(columnOverrides.get(overrideKey));
                inheritedCol.setNullable(isOverriddenNullable(entityClassDecl.getAnnotationByName("AttributeOverrides").orElse(null), overrideKey));
                log.trace("Applied inherited @AttributeOverride on {} for field '{}': -> '{}', nullable: {}", entityInfo.getJavaClassName(), overrideKey, inheritedCol.getColumnName(), inheritedCol.getNullable());
            }
            addIfNotPresent(entityInfo.getColumns(), inheritedCol, true, superClassName);
        });
        parentInfo.getRelationships().forEach(rel -> {
            RelationshipInfo inheritedRel = deepCopyRelationshipInfo(rel);
            addIfNotPresent(entityInfo.getRelationships(), inheritedRel, true, superClassName);
        });
    }

    private void parseField(FieldDeclaration field, List<ColumnInfo> columns, List<RelationshipInfo> relationships, EntityInfo entityContext, MappedSuperclassInfo superclassContext, boolean isEmbeddedAttribute, boolean inheritedFromMappedSuperclass) {
        if (field.isStatic() || field.isTransient() || field.isAnnotationPresent("Transient")) {
            return;
        }
        field.getVariables().forEach(variable -> {
            String fieldName = variable.getNameAsString();
            String fieldTypeName = "UNKNOWN";
            ResolvedType resolvedType = null;
            try {
                resolvedType = variable.getType().resolve();
                fieldTypeName = getResolvedTypeName(resolvedType);
            } catch (Exception e) {
                log.warn("Could not resolve type for field '{}' (using AST type '{}'): {}", fieldName, variable.getTypeAsString(), e.getMessage());
                fieldTypeName = variable.getTypeAsString();
            }
            log.trace("Parsing field: {} {}, Inherited: {}, EmbeddedAttr: {}", fieldTypeName, fieldName, inheritedFromMappedSuperclass, isEmbeddedAttribute);
            if (isRelationship(field)) {
                if (relationships != null) {
                    RelationshipInfo relInfo = parseRelationship(field, fieldName, fieldTypeName, resolvedType);
                    if (relInfo != null) {
                        addIfNotPresent(relationships, relInfo, inheritedFromMappedSuperclass, superclassContext != null ? superclassContext.getJavaClassName() : null);
                    }
                }
            } else if (field.isAnnotationPresent("Embedded") || field.isAnnotationPresent("EmbeddedId")) {
                if (columns != null && (entityContext != null || superclassContext != null)) {
                    EmbeddableInfo embeddedDef = findEmbeddableDefinition(fieldTypeName);
                    if (embeddedDef != null) {
                        String currentClassName = entityContext != null ? entityContext.getJavaClassName() : (superclassContext != null ? superclassContext.getJavaClassName() : "UNKNOWN");
                        log.debug("Processing @Embedded field '{}' of type '{}' in class '{}'", fieldName, fieldTypeName, currentClassName);
                        Map<String, String> overrides = getAttributeOverrides(field.getAnnotationByName("AttributeOverrides").orElse(null));
                        embeddedDef.getFields().forEach(embeddableColTmpl -> {
                            ColumnInfo col = deepCopyColumnInfo(embeddableColTmpl);
                            col.setIsEmbeddedAttribute(true);
                            col.setEmbeddedFromFieldName(fieldName);
                            col.setOriginalEmbeddableFieldName(embeddableColTmpl.getFieldName());
                            col.setInherited(inheritedFromMappedSuperclass);
                            if (inheritedFromMappedSuperclass && superclassContext != null)
                                col.setInheritedFromClass(superclassContext.getJavaClassName());
                            String overrideKey = embeddableColTmpl.getFieldName();
                            if (overrides.containsKey(overrideKey)) {
                                col.setColumnName(overrides.get(overrideKey));
                                col.setNullable(isOverriddenNullable(field.getAnnotationByName("AttributeOverrides").orElse(null), overrideKey));
                                log.trace("Applied @AttributeOverride for embedded field '{}' ({}): name='{}', nullable={}", fieldName, overrideKey, col.getColumnName(), col.getNullable());
                            } else {
                                col.setColumnName(toSnakeCase(fieldName) + "_" + col.getColumnName());
                                col.setNullable(embeddableColTmpl.getNullable());
                            }
                            if (field.isAnnotationPresent("EmbeddedId")) {
                                col.setPrimaryKey(true);
                                col.setNullable(false);
                            }
                            addIfNotPresent(columns, col, inheritedFromMappedSuperclass, col.getInheritedFromClass());
                        });
                    } else {
                        String currentClassName = entityContext != null ? entityContext.getJavaClassName() : (superclassContext != null ? superclassContext.getJavaClassName() : "UNKNOWN");
                        log.warn("Could not find @Embeddable definition for type '{}' used in @Embedded field '{}' of class '{}'", fieldTypeName, fieldName, currentClassName);
                    }
                }
            } else {
                if (columns != null) {
                    ColumnInfo colInfo = parseBasicColumn(field, fieldName, fieldTypeName, resolvedType);
                    if (colInfo != null) {
                        addIfNotPresent(columns, colInfo, inheritedFromMappedSuperclass, superclassContext != null ? superclassContext.getJavaClassName() : null);
                    }
                }
            }
        });
    }

    private ColumnInfo parseBasicColumn(FieldDeclaration field, String fieldName, String fieldType, ResolvedType resolvedType) {
        ColumnInfo col = new ColumnInfo();
        col.setFieldName(fieldName);
        col.setJavaType(fieldType);
        col.setSqlType(guessSqlType(fieldType));
        field.getAnnotationByName("Id").ifPresent(ann -> col.setPrimaryKey(true));
        field.getAnnotationByName("GeneratedValue").ifPresent(ann -> extractAnnotationAttribute(ann, "strategy").ifPresent(strategy -> col.setGenerationStrategy(strategy.replace("GenerationType.", ""))));
        field.getAnnotationByName("Column").ifPresent(ann -> {
            extractAnnotationAttribute(ann, "name").ifPresent(col::setColumnName);
            extractAnnotationAttribute(ann, "nullable").map(Boolean::parseBoolean).ifPresent(col::setNullable);
            extractAnnotationAttribute(ann, "unique").map(Boolean::parseBoolean).ifPresent(col::setUnique);
            extractAnnotationAttribute(ann, "length").map(Integer::parseInt).ifPresent(col::setLength);
            extractAnnotationAttribute(ann, "precision").map(Integer::parseInt).ifPresent(col::setPrecision);
            extractAnnotationAttribute(ann, "scale").map(Integer::parseInt).ifPresent(col::setScale);
        });
        if (col.getColumnName() == null) {
            col.setColumnName(toSnakeCase(fieldName));
        }
        if (col.getNullable() == null) {
            col.setNullable(!col.isPrimaryKey());
        }
        if (col.getUnique() == null) {
            col.setUnique(col.isPrimaryKey());
        }
        field.getAnnotationByName("Enumerated").ifPresent(ann -> {
            col.setIsEnum(true);
            EnumInfo enumInfo = new EnumInfo();
            String storage = extractAnnotationAttribute(ann, null).orElse("ORDINAL");
            enumInfo.setStorageType(storage.replace("EnumType.", ""));
            col.setSqlType("STRING".equals(enumInfo.getStorageType()) ? "VARCHAR" : "INTEGER");
            if (resolvedType != null && resolvedType.isReferenceType()) {
                try {
                    resolvedType.asReferenceType().getTypeDeclaration().filter(ResolvedReferenceTypeDeclaration::isEnum).ifPresent(enumDecl -> enumInfo.setPossibleValues(enumDecl.asEnum().getEnumConstants().stream().map(constant -> constant.getName()).collect(Collectors.toList())));
                } catch (Exception e) {
                    log.warn("Could not resolve enum constants for type {}: {}", fieldType, e.getMessage());
                }
            }
            if (enumInfo.getPossibleValues() == null) enumInfo.setPossibleValues(new ArrayList<>());
            col.setEnumInfo(enumInfo);
        });
        return col;
    }


    private RelationshipInfo parseRelationship(FieldDeclaration field, String fieldName, String fieldType, ResolvedType resolvedFieldType) {
        RelationshipInfo rel = new RelationshipInfo();
        rel.setFieldName(fieldName);
        String targetEntityType = fieldType;
        boolean isCollection = false;

        if (resolvedFieldType != null && resolvedFieldType.isReferenceType()) {
            String qualifiedName = resolvedFieldType.asReferenceType().getQualifiedName();
            if (qualifiedName.equals("java.util.List") || qualifiedName.equals("java.util.Set") || qualifiedName.equals("java.util.Collection") || qualifiedName.equals("java.lang.Iterable")) {
                isCollection = true;
                targetEntityType = getGenericTypeArgument(field, resolvedFieldType); // Static call OK
            }
        }

        if (field.isAnnotationPresent("OneToOne")) {
            rel.setType("OneToOne");
            field.getAnnotationByName("OneToOne").ifPresent(ann -> parseCommonRelationshipAttributes(rel, ann));
        } else if (field.isAnnotationPresent("OneToMany")) {
            rel.setType("OneToMany");
            if (!isCollection) log.warn("@OneToMany used on non-collection field '{}' type '{}'", fieldName, fieldType);
            rel.setOwningSide(false);
            field.getAnnotationByName("OneToMany").ifPresent(ann -> parseCommonRelationshipAttributes(rel, ann));
        } else if (field.isAnnotationPresent("ManyToOne")) {
            rel.setType("ManyToOne");
            if (isCollection) log.warn("@ManyToOne used on collection field '{}' type '{}'", fieldName, fieldType);
            rel.setOwningSide(true);
            field.getAnnotationByName("ManyToOne").ifPresent(ann -> parseCommonRelationshipAttributes(rel, ann));
        } else if (field.isAnnotationPresent("ManyToMany")) {
            rel.setType("ManyToMany");
            if (!isCollection)
                log.warn("@ManyToMany used on non-collection field '{}' type '{}'", fieldName, fieldType);
            field.getAnnotationByName("ManyToMany").ifPresent(ann -> {
                parseCommonRelationshipAttributes(rel, ann);
                if (rel.getMappedBy() != null) rel.setOwningSide(false);
                else rel.setOwningSide(true);
            });
        } else {
            return null;
        }

        if (targetEntityType == null || targetEntityType.equals("UNKNOWN") || targetEntityType.equals("java.lang.Object") || targetEntityType.equals("ERROR_RESOLVING_TYPE")) {
            log.warn("Could not determine target entity type for relationship field: {}. Annotation: {}", fieldName, rel.getType());
            return null;
        }
        rel.setTargetEntityJavaClass(targetEntityType);

        field.getAnnotationByName("JoinColumn").ifPresent(ann -> {
            extractAnnotationAttribute(ann, "name").ifPresent(rel::setJoinColumnName); // Static call OK
        });
        if (rel.isOwningSide() && rel.getJoinColumnName() == null && ("ManyToOne".equals(rel.getType()) || "OneToOne".equals(rel.getType()))) {
            rel.setJoinColumnName(toSnakeCase(fieldName) + "_id"); // Static call OK
        }

        field.getAnnotationByName("JoinTable").ifPresent(ann -> {
            extractAnnotationAttribute(ann, "name").ifPresent(rel::setJoinTableName); // Static call OK
            extractJoinColumnNameFromAnnotationExpr(ann, "joinColumns").ifPresent(rel::setJoinTableJoinColumnName); // Static call OK
            extractJoinColumnNameFromAnnotationExpr(ann, "inverseJoinColumns").ifPresent(rel::setJoinTableInverseJoinColumnName); // Static call OK
            if (rel.getMappedBy() == null && ("ManyToMany".equals(rel.getType()) || "OneToMany".equals(rel.getType()))) {
                rel.setOwningSide(true);
            }
        });

        return rel;
    }

    private void parseCommonRelationshipAttributes(RelationshipInfo rel, AnnotationExpr ann) { // Instance method
        extractAnnotationAttribute(ann, "mappedBy").ifPresent(rel::setMappedBy); // Static call OK
        extractAnnotationAttribute(ann, "fetch").ifPresentOrElse( // Static call OK
                val -> rel.setFetchType(val.replace("FetchType.", "")),
                () -> { /* Default set later */ }
        );
        extractAnnotationAttributeList(ann, "cascade").ifPresent(rel::setCascadeTypes); // Static call OK
        if (rel.getFetchType() == null) {
            if ("ManyToOne".equals(rel.getType()) || "OneToOne".equals(rel.getType())) {
                rel.setFetchType("EAGER");
            } else {
                rel.setFetchType("LAZY");
            }
        }
    }


    private static boolean isRelationship(FieldDeclaration field) {
        return field.isAnnotationPresent("OneToOne") || field.isAnnotationPresent("OneToMany") || field.isAnnotationPresent("ManyToOne") || field.isAnnotationPresent("ManyToMany");
    }

    private static String getResolvedTypeName(ResolvedType resolvedType) {
        try {
            if (resolvedType.isReferenceType()) {
                ResolvedReferenceType refType = resolvedType.asReferenceType();
                if (refType.getQualifiedName().equals("java.util.Optional")) {
                    Optional<ResolvedType> typeParamOpt = refType.getTypeParametersMap().stream()
                            .map(pair -> Optional.ofNullable(pair.b))               // Map to Optional<ResolvedType>
                            .filter(Optional::isPresent) // Filter non-empty Optionals using lambda
                            .map(Optional::get)          // Get ResolvedType from Optional
                            .findFirst();                  // Get the first type parameter
                    if (typeParamOpt.isPresent()) {
                        return "Optional<" + getResolvedTypeName(typeParamOpt.get()) + ">";
                    } else {
                        log.warn("Could not resolve type parameter for Optional type: {}", refType.getQualifiedName());
                        return "Optional<UNKNOWN>";
                    }
                }
                return refType.getQualifiedName();
            } else if (resolvedType.isPrimitive()) {
                return resolvedType.asPrimitive().name().toLowerCase();
            } else if (resolvedType.isArray()) {
                if (resolvedType.asArrayType().getComponentType().isPrimitive() &&
                        resolvedType.asArrayType().getComponentType().equals(ResolvedPrimitiveType.BYTE)) {
                    return "byte[]";
                }
                return getResolvedTypeName(resolvedType.asArrayType().getComponentType()) + "[]";
            } else if (resolvedType.isVoid()) {
                return ResolvedVoidType.INSTANCE.describe();
            }
            return resolvedType.describe();
        } catch (Exception e) {
            log.warn("Error describing resolved type '{}': {}", resolvedType.toString(), e.getMessage());
            return "ERROR_RESOLVING_TYPE";
        }
    }

    // *** ИСПРАВЛЕННЫЙ getGenericTypeArgument ***
    private static String getGenericTypeArgument(FieldDeclaration field, ResolvedType resolvedFieldType) {
        try {
            if (resolvedFieldType != null && resolvedFieldType.isReferenceType()) {
                ResolvedReferenceType refType = resolvedFieldType.asReferenceType();

                Optional<ResolvedType> typeArgOpt = refType.getTypeParametersMap().stream()
                        .map(pair -> Optional.ofNullable(pair.b))         // Map to Optional<ResolvedType>
                        .filter(Optional::isPresent) // Use lambda here
                        .map(Optional::get)         // Extract ResolvedType
                        .findFirst();                  // Get the first one

                if (typeArgOpt.isPresent()) {
                    return getResolvedTypeName(typeArgOpt.get());
                } else {
                    log.warn("Could not find type arguments for generic type '{}' in field '{}'", refType.getQualifiedName(), field.getVariable(0).getNameAsString());
                }
            }
        } catch (Exception e) {
            log.warn("Error extracting generic type argument for field '{}'", field.getVariable(0).getNameAsString(), e);
        }
        return "java.lang.Object";
    }

    private static Optional<String> extractAnnotationAttribute(AnnotationExpr annotationExpr, String attributeName) {
        if (annotationExpr == null) return Optional.empty();
        final String targetAttribute = (attributeName == null || attributeName.isEmpty()) ? "value" : attributeName;
        if (annotationExpr.isSingleMemberAnnotationExpr()) {
            return "value".equals(targetAttribute) ? Optional.ofNullable(getStringValue(annotationExpr.asSingleMemberAnnotationExpr().getMemberValue())) : Optional.empty();
        }
        if (annotationExpr.isNormalAnnotationExpr()) {
            return annotationExpr.asNormalAnnotationExpr().getPairs().stream().filter(pair -> targetAttribute.equals(pair.getNameAsString())).findFirst().map(pair -> getStringValue(pair.getValue()));
        }
        return Optional.empty();
    }

    private static Optional<List<String>> extractAnnotationAttributeList(AnnotationExpr annotationExpr, String attributeName) {
        if (annotationExpr == null || attributeName == null || !annotationExpr.isNormalAnnotationExpr()) {
            return Optional.empty();
        }
        Optional<Expression> valueExprOpt = annotationExpr.asNormalAnnotationExpr().getPairs().stream().filter(pair -> attributeName.equals(pair.getNameAsString())).findFirst().map(MemberValuePair::getValue);
        if (valueExprOpt.isPresent()) {
            Expression valueExpr = valueExprOpt.get();
            if (valueExpr.isArrayInitializerExpr()) {
                List<String> values = new ArrayList<>();
                valueExpr.asArrayInitializerExpr().getValues().forEach(valExpr -> {
                    String strVal = getStringValue(valExpr);
                    if (strVal != null) {
                        values.add(strVal.substring(strVal.lastIndexOf('.') + 1));
                    }
                });
                return Optional.of(values).filter(l -> !l.isEmpty());
            } else {
                String singleVal = getStringValue(valueExpr);
                if (singleVal != null) {
                    return Optional.of(List.of(singleVal.substring(singleVal.lastIndexOf('.') + 1)));
                }
            }
        }
        return Optional.empty();
    }

    private static String getStringValue(Expression expr) {
        if (expr == null) return null;
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        } else if (expr.isNameExpr() || expr.isFieldAccessExpr()) {
            return expr.toString();
        } else if (expr.isIntegerLiteralExpr()) {
            return expr.asIntegerLiteralExpr().getValue();
        } else if (expr.isBooleanLiteralExpr()) {
            return String.valueOf(expr.asBooleanLiteralExpr().getValue());
        } else if (expr.isLongLiteralExpr()) {
            return expr.asLongLiteralExpr().getValue();
        } else if (expr.isDoubleLiteralExpr()) {
            return expr.asDoubleLiteralExpr().getValue();
        } else if (expr.isAnnotationExpr()) {
            return expr.toString();
        }
        log.trace("Could not extract simple string value from expression: {} (Type: {})", expr, expr.getClass().getSimpleName());
        return null;
    }

    private static String toSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return "";
        return camelCase.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z\\d])([A-Z])", "$1_$2").toLowerCase();
    }

    private static Map<String, String> createJavaToSqlTypeMap() {
        Map<String, String> map = new HashMap<>();
        map.put("String", "VARCHAR");
        map.put("Long", "BIGINT");
        map.put("long", "BIGINT");
        map.put("Integer", "INTEGER");
        map.put("int", "INTEGER");
        map.put("Short", "SMALLINT");
        map.put("short", "SMALLINT");
        map.put("Double", "DOUBLE PRECISION");
        map.put("double", "DOUBLE PRECISION");
        map.put("Float", "REAL");
        map.put("float", "REAL");
        map.put("Boolean", "BOOLEAN");
        map.put("boolean", "BOOLEAN");
        map.put("LocalDate", "DATE");
        map.put("LocalDateTime", "TIMESTAMP");
        map.put("ZonedDateTime", "TIMESTAMP WITH TIME ZONE");
        map.put("OffsetDateTime", "TIMESTAMP WITH TIME ZONE");
        map.put("Instant", "TIMESTAMP WITH TIME ZONE");
        map.put("Date", "TIMESTAMP");
        map.put("Timestamp", "TIMESTAMP");
        map.put("Time", "TIME");
        map.put("BigDecimal", "NUMERIC");
        map.put("BigInteger", "NUMERIC");
        map.put("byte[]", "BYTEA");
        map.put("Byte[]", "BYTEA");
        map.put("UUID", "UUID");
        map.put("Character", "CHAR(1)");
        map.put("char", "CHAR(1)");
        return Collections.unmodifiableMap(map);
    }

    private static String guessSqlType(String javaType) {
        String simpleJavaType = javaType;
        if (javaType.contains(".")) {
            simpleJavaType = javaType.substring(javaType.lastIndexOf('.') + 1);
        }
        if (simpleJavaType.startsWith("Optional<") && simpleJavaType.endsWith(">")) {
            String innerType = simpleJavaType.substring(9, simpleJavaType.length() - 1);
            String simpleInnerType = innerType.substring(innerType.lastIndexOf('.') + 1);
            return JAVA_TO_SQL_TYPE_MAP.getOrDefault(simpleInnerType, "VARCHAR");
        }
        return JAVA_TO_SQL_TYPE_MAP.getOrDefault(simpleJavaType, "VARCHAR");
    }

    private static Optional<String> extractJoinColumnNameFromAnnotationExpr(AnnotationExpr parentAnn, String attributeName) {
        return extractAnnotationAttribute(parentAnn, attributeName).flatMap(annString -> {
            if (annString.startsWith("{") && annString.endsWith("}")) {
                String content = annString.substring(1, annString.length() - 1).trim();
                if (content.startsWith("@JoinColumn")) {
                    annString = content.split(",")[0].trim();
                } else {
                    return Optional.empty();
                }
            }
            if (annString.startsWith("@JoinColumn")) {
                try {
                    AnnotationExpr joinColumnAnn = StaticJavaParser.parseAnnotation(annString);
                    return extractAnnotationAttribute(joinColumnAnn, "name");
                } catch (Exception e) {
                    log.warn("Failed to parse @JoinColumn string: {}", annString, e);
                    int nameIndex = annString.indexOf("name");
                    if (nameIndex > 0) {
                        int equalsIndex = annString.indexOf('=', nameIndex);
                        int endIndex = annString.indexOf(',', equalsIndex);
                        if (endIndex == -1) endIndex = annString.indexOf(')', equalsIndex);
                        if (equalsIndex > 0 && endIndex > equalsIndex) {
                            String nameVal = annString.substring(equalsIndex + 1, endIndex).trim();
                            if (nameVal.startsWith("\"") && nameVal.endsWith("\"")) {
                                nameVal = nameVal.substring(1, nameVal.length() - 1);
                            }
                            return Optional.of(nameVal);
                        }
                    }
                    return Optional.empty();
                }
            }
            return Optional.empty();
        });
    }

    private static Map<String, String> getAttributeOverrides(AnnotationExpr overridesAnnotation) {
        Map<String, String> overrides = new HashMap<>();
        if (overridesAnnotation == null || !overridesAnnotation.isAnnotationExpr()) return overrides;
        Expression valueExpr = null;
        if (overridesAnnotation.isNormalAnnotationExpr()) {
            valueExpr = overridesAnnotation.asNormalAnnotationExpr().getPairs().stream().filter(p -> "value".equals(p.getNameAsString())).findFirst().map(MemberValuePair::getValue).orElse(null);
        } else if (overridesAnnotation.isSingleMemberAnnotationExpr()) {
            valueExpr = overridesAnnotation.asSingleMemberAnnotationExpr().getMemberValue();
        } else {
            return overrides;
        }
        if (valueExpr != null && valueExpr.isArrayInitializerExpr()) {
            valueExpr.asArrayInitializerExpr().getValues().forEach(overrideNode -> {
                if (overrideNode instanceof AnnotationExpr overrideAnn && overrideAnn.getNameAsString().equals("AttributeOverride")) {
                    Optional<String> nameOpt = extractAnnotationAttribute(overrideAnn, "name");
                    Optional<String> columnOpt = extractAnnotationAttribute(overrideAnn, "column").flatMap(colAnnStr -> {
                        try {
                            return extractAnnotationAttribute(StaticJavaParser.parseAnnotation(colAnnStr), "name");
                        } catch (Exception e) {
                            return Optional.empty();
                        }
                    });
                    if (nameOpt.isPresent() && columnOpt.isPresent()) {
                        overrides.put(nameOpt.get(), columnOpt.get());
                    }
                }
            });
        } else if (valueExpr instanceof AnnotationExpr overrideAnn && overrideAnn.getNameAsString().equals("AttributeOverride")) {
            Optional<String> nameOpt = extractAnnotationAttribute(overrideAnn, "name");
            Optional<String> columnOpt = extractAnnotationAttribute(overrideAnn, "column").flatMap(colAnnStr -> {
                try {
                    return extractAnnotationAttribute(StaticJavaParser.parseAnnotation(colAnnStr), "name");
                } catch (Exception e) {
                    return Optional.empty();
                }
            });
            if (nameOpt.isPresent() && columnOpt.isPresent()) {
                overrides.put(nameOpt.get(), columnOpt.get());
            }
        }
        return overrides;
    }

    private static Boolean isOverriddenNullable(AnnotationExpr overridesAnnotation, String fieldName) {
        if (overridesAnnotation == null || !overridesAnnotation.isAnnotationExpr()) return null;
        Expression valueExpr = null;
        if (overridesAnnotation.isNormalAnnotationExpr()) {
            valueExpr = overridesAnnotation.asNormalAnnotationExpr().getPairs().stream().filter(p -> "value".equals(p.getNameAsString())).findFirst().map(MemberValuePair::getValue).orElse(null);
        } else if (overridesAnnotation.isSingleMemberAnnotationExpr()) {
            valueExpr = overridesAnnotation.asSingleMemberAnnotationExpr().getMemberValue();
        } else {
            return null;
        }
        if (valueExpr != null && valueExpr.isArrayInitializerExpr()) {
            for (Node overrideNode : valueExpr.asArrayInitializerExpr().getValues()) {
                if (overrideNode instanceof AnnotationExpr overrideAnn && overrideAnn.getNameAsString().equals("AttributeOverride")) {
                    Optional<String> nameOpt = extractAnnotationAttribute(overrideAnn, "name");
                    if (nameOpt.isPresent() && nameOpt.get().equals(fieldName)) {
                        return extractAnnotationAttribute(overrideAnn, "column").flatMap(colAnnStr -> {
                            try {
                                return extractAnnotationAttribute(StaticJavaParser.parseAnnotation(colAnnStr), "nullable");
                            } catch (Exception e) {
                                return Optional.empty();
                            }
                        }).map(Boolean::parseBoolean).orElse(null);
                    }
                }
            }
        } else if (valueExpr instanceof AnnotationExpr overrideAnn && overrideAnn.getNameAsString().equals("AttributeOverride")) {
            Optional<String> nameOpt = extractAnnotationAttribute(overrideAnn, "name");
            if (nameOpt.isPresent() && nameOpt.get().equals(fieldName)) {
                return extractAnnotationAttribute(overrideAnn, "column").flatMap(colAnnStr -> {
                    try {
                        return extractAnnotationAttribute(StaticJavaParser.parseAnnotation(colAnnStr), "nullable");
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                }).map(Boolean::parseBoolean).orElse(null);
            }
        }
        return null;
    }


    private EmbeddableInfo findEmbeddableDefinition(String qualifiedClassName) {
        return embeddables.stream().filter(e -> qualifiedClassName.equals(e.getJavaClassName())).findFirst().orElse(null);
    }

    private ColumnInfo deepCopyColumnInfo(ColumnInfo original) {
        ColumnInfo copy = new ColumnInfo();
        copy.setFieldName(original.getFieldName());
        copy.setColumnName(original.getColumnName());
        copy.setJavaType(original.getJavaType());
        copy.setSqlType(original.getSqlType());
        copy.setPrimaryKey(original.isPrimaryKey());
        copy.setGenerationStrategy(original.getGenerationStrategy());
        copy.setNullable(original.getNullable());
        copy.setUnique(original.getUnique());
        copy.setLength(original.getLength());
        copy.setPrecision(original.getPrecision());
        copy.setScale(original.getScale());
        copy.setIsEnum(original.getIsEnum());
        if (original.getEnumInfo() != null) {
            EnumInfo eiCopy = new EnumInfo();
            eiCopy.setStorageType(original.getEnumInfo().getStorageType());
            eiCopy.setPossibleValues(original.getEnumInfo().getPossibleValues() != null ? new ArrayList<>(original.getEnumInfo().getPossibleValues()) : new ArrayList<>());
            copy.setEnumInfo(eiCopy);
        }
        copy.setIsEmbeddedAttribute(original.getIsEmbeddedAttribute());
        copy.setEmbeddedFromFieldName(original.getEmbeddedFromFieldName());
        copy.setOriginalEmbeddableFieldName(original.getOriginalEmbeddableFieldName());
        copy.setInherited(original.getInherited());
        copy.setInheritedFromClass(original.getInheritedFromClass());
        return copy;
    }

    private RelationshipInfo deepCopyRelationshipInfo(RelationshipInfo original) {
        RelationshipInfo copy = new RelationshipInfo();
        copy.setFieldName(original.getFieldName());
        copy.setType(original.getType());
        copy.setTargetEntityJavaClass(original.getTargetEntityJavaClass());
        copy.setMappedBy(original.getMappedBy());
        copy.setFetchType(original.getFetchType());
        copy.setCascadeTypes(original.getCascadeTypes() != null ? new ArrayList<>(original.getCascadeTypes()) : null);
        copy.setOwningSide(original.isOwningSide());
        copy.setJoinColumnName(original.getJoinColumnName());
        copy.setJoinTableName(original.getJoinTableName());
        copy.setJoinTableJoinColumnName(original.getJoinTableJoinColumnName());
        copy.setJoinTableInverseJoinColumnName(original.getJoinTableInverseJoinColumnName());
        copy.setInherited(original.getInherited());
        copy.setInheritedFromClass(original.getInheritedFromClass());
        return copy;
    }

    private void addIfNotPresent(List<ColumnInfo> list, ColumnInfo itemToAdd, boolean inherited, String inheritedFrom) {
        if (list.stream().noneMatch(existing -> existing.getFieldName().equals(itemToAdd.getFieldName()))) {
            itemToAdd.setInherited(inherited);
            if (inherited) itemToAdd.setInheritedFromClass(inheritedFrom);
            list.add(itemToAdd);
        } else {
            log.trace("Skipping adding inherited/duplicate column field: {}", itemToAdd.getFieldName());
        }
    }

    private void addIfNotPresent(List<ColumnInfo> list, ColumnInfo itemToAdd) {
        addIfNotPresent(list, itemToAdd, false, null);
    }

    private void addIfNotPresent(List<RelationshipInfo> list, RelationshipInfo itemToAdd, boolean inherited, String inheritedFrom) {
        if (list.stream().noneMatch(existing -> existing.getFieldName().equals(itemToAdd.getFieldName()))) {
            itemToAdd.setInherited(inherited);
            if (inherited) itemToAdd.setInheritedFromClass(inheritedFrom);
            list.add(itemToAdd);
        } else {
            log.trace("Skipping adding inherited/duplicate relationship field: {}", itemToAdd.getFieldName());
        }
    }

    @Data
    public static class MappedSuperclassInfo {
        private String javaClassName;
        private List<ColumnInfo> columns = new ArrayList<>();
        private List<RelationshipInfo> relationships = new ArrayList<>();
    }
}