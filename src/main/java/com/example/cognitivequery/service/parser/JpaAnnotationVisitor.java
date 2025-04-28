package com.example.cognitivequery.service.parser;

import com.example.cognitivequery.model.ir.*; // Import IR models
import com.github.javaparser.StaticJavaParser; // For parsing annotation strings
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node; // Import Node
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration; // For checking interfaces
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import lombok.Data; // For internal helper class
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class JpaAnnotationVisitor extends VoidVisitorAdapter<Object> {

    // Enum to control visitor behavior during passes
    public enum ProcessingPass { FIRST_PASS, SECOND_PASS }

    private final Map<String, MappedSuperclassInfo> mappedSuperclasses;
    private final List<EmbeddableInfo> embeddables;
    private final SchemaInfo schemaInfo; // Populated only in SECOND_PASS

    private static final Map<String, String> JAVA_TO_SQL_TYPE_MAP = createJavaToSqlTypeMap();

    // Constructor for FIRST_PASS
    public JpaAnnotationVisitor(Map<String, MappedSuperclassInfo> mappedSuperclasses, List<EmbeddableInfo> embeddables) {
        this(mappedSuperclasses, embeddables, null);
    }

    // Main constructor used for SECOND_PASS
    public JpaAnnotationVisitor(Map<String, MappedSuperclassInfo> mappedSuperclasses, List<EmbeddableInfo> embeddables, SchemaInfo schemaInfo) {
        this.mappedSuperclasses = mappedSuperclasses;
        this.embeddables = embeddables != null ? embeddables : new ArrayList<>(); // Ensure list is not null
        this.schemaInfo = schemaInfo;
    }

    @Override
    public void visit(CompilationUnit cu, Object arg) {
        super.visit(cu, arg);
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration cl, Object arg) {
        super.visit(cl, arg);

        // Ignore interfaces and classes without annotations we care about in the first place
        if (cl.isInterface() || !(cl.isAnnotationPresent("Entity") || cl.isAnnotationPresent("MappedSuperclass") || cl.isAnnotationPresent("Embeddable"))) {
            return;
        }

        String className = cl.getFullyQualifiedName().orElse(cl.getNameAsString());

        ProcessingPass currentPass = (arg instanceof ProcessingPass) ? (ProcessingPass) arg : null;
        if (currentPass == null) {
            log.trace("Visitor argument is not ProcessingPass, skipping class {}", className);
            return; // Skip if context is wrong
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


    private MappedSuperclassInfo parseMappedSuperclass(ClassOrInterfaceDeclaration cl) {
        String className = cl.getFullyQualifiedName().orElse(cl.getNameAsString());
        log.trace("Parsing MappedSuperclass details for: {}", className);
        MappedSuperclassInfo info = new MappedSuperclassInfo();
        info.setJavaClassName(className);

        // Add fields declared directly in this MappedSuperclass
        for (FieldDeclaration field : cl.getFields()) {
            parseField(field, info.getColumns(), info.getRelationships(), null, info, false, false);
        }

        // Recursively add fields from parent MappedSuperclass
        addInheritedMappedSuperclassFields(cl, info);

        return info;
    }

    private EmbeddableInfo parseEmbeddable(ClassOrInterfaceDeclaration cl) {
        String className = cl.getFullyQualifiedName().orElse(cl.getNameAsString());
        log.trace("Parsing Embeddable details for: {}", className);
        EmbeddableInfo info = new EmbeddableInfo();
        info.setJavaClassName(className);
        for (FieldDeclaration field : cl.getFields()) {
            // Embeddables only have basic fields, not relationships or other embeddeds directly
            parseField(field, info.getFields(), null, null, null, false, false);
        }
        // Note: Embeddables inheriting from other Embeddables or MappedSuperclasses is complex and not fully handled here.
        return info;
    }

    private EntityInfo parseEntity(ClassOrInterfaceDeclaration cl) {
        String className = cl.getFullyQualifiedName().orElse(cl.getNameAsString());
        log.trace("Parsing Entity details for: {}", className);
        EntityInfo info = new EntityInfo();
        info.setJavaClassName(className);

        // Table name
        info.setTableName(extractAnnotationAttribute(cl.getAnnotationByName("Table").orElse(null), "name")
                .orElse(toSnakeCase(cl.getNameAsString())));

        // Handle inheritance from MappedSuperclass
        cl.getExtendedTypes().stream()
                .findFirst() // Assuming single inheritance for simplicity here
                .ifPresent(extendedType -> {
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
                                // Handle inheritance from other Entities if needed (JPA inheritance strategies) - NOT IMPLEMENTED
                            }
                        }
                    } catch (UnsolvedSymbolException e) {
                        log.warn("Could not resolve superclass symbol {} for Entity {}", extendedType.getNameAsString(), className);
                    } catch (Exception e) {
                        log.warn("Error resolving superclass {} for Entity {}", extendedType.getNameAsString(), className, e);
                    }
                });

        // Parse fields declared directly in the entity
        log.trace("Parsing direct fields for Entity {}", className);
        for (FieldDeclaration field : cl.getFields()) {
            parseField(field, info.getColumns(), info.getRelationships(), info, null, false, false);
        }

        return info;
    }

    // Recursive helper for MappedSuperclass inheritance
    private void addInheritedMappedSuperclassFields(ClassOrInterfaceDeclaration cl, MappedSuperclassInfo currentInfo) {
        cl.getExtendedTypes().stream()
                .findFirst()
                .ifPresent(extendedType -> {
                    try {
                        ResolvedType resolved = extendedType.resolve();
                        if (resolved.isReferenceType()) {
                            String superClassName = resolved.asReferenceType().getQualifiedName();
                            MappedSuperclassInfo parentInfo = mappedSuperclasses.get(superClassName);
                            if (parentInfo != null) {
                                log.trace("Adding inherited fields from {} to {}", superClassName, currentInfo.getJavaClassName());
                                // Add parent fields first (recursive call)
                                // Need to resolve parent class declaration to continue recursion - this requires symbol solver setup
                                resolved.asReferenceType().getTypeDeclaration()
                                        .filter(ResolvedReferenceTypeDeclaration::isClass)
                                        .flatMap(td -> td.asClass().toAst()) // Convert resolved type back to AST node
                                        .ifPresent(parentCl -> addInheritedMappedSuperclassFields(parentCl, currentInfo)); // Recursive call


                                // Then add fields from this direct parent
                                parentInfo.getColumns().forEach(col -> addIfNotPresent(currentInfo.getColumns(), deepCopyColumnInfo(col), true, superClassName));
                                parentInfo.getRelationships().forEach(rel -> addIfNotPresent(currentInfo.getRelationships(), deepCopyRelationshipInfo(rel), true, superClassName));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Error resolving or processing superclass {} for {}", extendedType.getNameAsString(), currentInfo.getJavaClassName(), e);
                    }
                });
    }


    // Adds members from MappedSuperclass to Entity, handling overrides
    private void addInheritedMembers(EntityInfo entityInfo, String superClassName, ClassOrInterfaceDeclaration entityClassDecl) {
        MappedSuperclassInfo parentInfo = mappedSuperclasses.get(superClassName);
        if (parentInfo == null) return;

        Map<String, String> columnOverrides = getAttributeOverrides(entityClassDecl.getAnnotationByName("AttributeOverrides").orElse(null));
        // TODO: Parse @AssociationOverrides

        parentInfo.getColumns().forEach(col -> {
            ColumnInfo inheritedCol = deepCopyColumnInfo(col);
            // Apply override if present
            String overrideKey = inheritedCol.getFieldName();
            if (columnOverrides.containsKey(overrideKey)) {
                inheritedCol.setColumnName(columnOverrides.get(overrideKey));
                log.trace("Applied inherited @AttributeOverride on {} for field '{}': -> '{}'", entityInfo.getJavaClassName(), overrideKey, inheritedCol.getColumnName());
            }
            addIfNotPresent(entityInfo.getColumns(), inheritedCol, true, superClassName);
        });

        parentInfo.getRelationships().forEach(rel -> {
            RelationshipInfo inheritedRel = deepCopyRelationshipInfo(rel);
            // TODO: Apply @AssociationOverrides if needed
            addIfNotPresent(entityInfo.getRelationships(), inheritedRel, true, superClassName);
        });
    }

    private void parseField(FieldDeclaration field, List<ColumnInfo> columns, List<RelationshipInfo> relationships,
                            EntityInfo entityContext, MappedSuperclassInfo superclassContext, // Pass both for context
                            boolean isEmbeddedAttribute, boolean inheritedFromMappedSuperclass) {

        if (field.isStatic() || field.isTransient() || field.isAnnotationPresent("Transient")) {
            // log.trace("Skipping static/transient field: {}", field.getVariable(0).getNameAsString());
            return;
        }

        field.getVariables().forEach(variable -> {
            String fieldName = variable.getNameAsString();
            String fieldTypeName = "UNKNOWN"; // Default
            ResolvedType resolvedType = null;
            try {
                resolvedType = variable.getType().resolve();
                fieldTypeName = getResolvedTypeName(resolvedType);
            } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                log.warn("Could not resolve type for field '{}': {}. Using AST type '{}'.", fieldName, e.getMessage(), variable.getTypeAsString());
                fieldTypeName = variable.getTypeAsString(); // Fallback to AST string representation
            } catch (Exception e) {
                log.error("Unexpected error resolving type for field '{}'", fieldName, e);
                fieldTypeName = variable.getTypeAsString();
            }

            log.trace("Parsing field: {} {}, Inherited: {}, EmbeddedAttr: {}", fieldTypeName, fieldName, inheritedFromMappedSuperclass, isEmbeddedAttribute);

            // --- Handle Relationships ---
            if (isRelationship(field)) {
                if (relationships == null) {
                    // log.warn("Relationship field '{}' found outside of Entity/MappedSuperclass context. Ignoring.", fieldName);
                    return;
                }
                RelationshipInfo relInfo = parseRelationship(field, fieldName, fieldTypeName, resolvedType);
                if (relInfo != null) {
                    addIfNotPresent(relationships, relInfo, inheritedFromMappedSuperclass, superclassContext != null ? superclassContext.getJavaClassName() : null);
                }
            }
            // --- Handle Embedded ---
            else if (field.isAnnotationPresent("Embedded") || field.isAnnotationPresent("EmbeddedId")) {
                if (columns == null || entityContext == null) { // Embedded only makes sense in an Entity/MappedSuperclass context for columns
                    log.warn("@Embedded field '{}' found outside of Entity/MappedSuperclass column context. Ignoring for columns.", fieldName);
                    return;
                }
                EmbeddableInfo embeddedDef = findEmbeddableDefinition(fieldTypeName);
                if (embeddedDef != null) {
                    log.debug("Processing @Embedded field '{}' of type '{}' in class '{}'", fieldName, fieldTypeName, entityContext.getJavaClassName());
                    Map<String, String> overrides = getAttributeOverrides(field.getAnnotationByName("AttributeOverrides").orElse(null));

                    embeddedDef.getFields().forEach(embeddableColTmpl -> {
                        ColumnInfo col = deepCopyColumnInfo(embeddableColTmpl);
                        col.setEmbeddedAttribute(true);
                        col.setEmbeddedFromFieldName(fieldName);
                        col.setOriginalEmbeddableFieldName(embeddableColTmpl.getFieldName());
                        col.setInherited(inheritedFromMappedSuperclass); // If the @Embedded field itself was inherited
                        if (inheritedFromMappedSuperclass && superclassContext != null) col.setInheritedFromClass(superclassContext.getJavaClassName());

                        // Apply override or default naming
                        String overrideKey = embeddableColTmpl.getFieldName();
                        if (overrides.containsKey(overrideKey)) {
                            col.setColumnName(overrides.get(overrideKey));
                            col.setNullable(isOverriddenNullable(field.getAnnotationByName("AttributeOverrides").orElse(null), overrideKey)); // Check nullable override too
                            log.trace("Applied @AttributeOverride for embedded field '{}' ({}): name='{}', nullable={}",
                                    fieldName, overrideKey, col.getColumnName(), col.getNullable());
                        } else {
                            col.setColumnName(toSnakeCase(fieldName) + "_" + col.getColumnName()); // Default prefix
                            // Inherit nullability from embeddable definition by default
                            col.setNullable(embeddableColTmpl.getNullable());
                        }

                        // Check if @EmbeddedId is used
                        if (field.isAnnotationPresent("EmbeddedId")){
                            col.setPrimaryKey(true); // All columns from EmbeddedId are part of the PK
                        }

                        addIfNotPresent(columns, col);
                    });
                } else {
                    log.warn("Could not find @Embeddable definition for type '{}' used in @Embedded field '{}' of class '{}'", fieldTypeName, fieldName, entityContext.getJavaClassName());
                }
            }
            // --- Handle Basic Columns ---
            else {
                if (columns == null) {
                    log.trace("Basic field '{}' found outside of column context. Ignoring.", fieldName);
                    return; // Should not happen if called correctly
                }
                ColumnInfo colInfo = parseBasicColumn(field, fieldName, fieldTypeName, resolvedType);
                if (colInfo != null) {
                    addIfNotPresent(columns, colInfo, inheritedFromMappedSuperclass, superclassContext != null ? superclassContext.getJavaClassName() : null);
                }
            }
        });
    }

    private ColumnInfo parseBasicColumn(FieldDeclaration field, String fieldName, String fieldType, ResolvedType resolvedType) {
        ColumnInfo col = new ColumnInfo();
        col.setFieldName(fieldName);
        col.setJavaType(fieldType);
        col.setSqlType(guessSqlType(fieldType)); // Initial guess

        // --- Process Annotations ---
        field.getAnnotationByName("Id").ifPresent(ann -> col.setPrimaryKey(true));

        field.getAnnotationByName("GeneratedValue").ifPresent(ann -> {
            extractAnnotationAttribute(ann, "strategy")
                    .ifPresent(strategy -> col.setGenerationStrategy(strategy.replace("GenerationType.", "")));
        });

        field.getAnnotationByName("Column").ifPresent(ann -> {
            extractAnnotationAttribute(ann, "name").ifPresent(col::setColumnName);
            extractAnnotationAttribute(ann, "nullable").ifPresent(val -> col.setNullable(Boolean.parseBoolean(val)));
            extractAnnotationAttribute(ann, "unique").ifPresent(val -> col.setUnique(Boolean.parseBoolean(val)));
            extractAnnotationAttribute(ann, "length").ifPresent(val -> col.setLength(Integer.parseInt(val)));
            extractAnnotationAttribute(ann, "precision").ifPresent(val -> col.setPrecision(Integer.parseInt(val)));
            extractAnnotationAttribute(ann, "scale").ifPresent(val -> col.setScale(Integer.parseInt(val)));
            // Could also parse columnDefinition if needed
        });

        // Default column name
        if (col.getColumnName() == null) {
            col.setColumnName(toSnakeCase(fieldName));
        }
        // Set default nullable=true if not specified by @Column or @Id
        if (col.getNullable() == null && !col.isPrimaryKey()) {
            col.setNullable(true);
        } else if (col.getNullable() == null && col.isPrimaryKey()) {
            col.setNullable(false); // PKs are usually not nullable
        }

        field.getAnnotationByName("Enumerated").ifPresent(ann -> {
            col.setIsEnum(true);
            EnumInfo enumInfo = new EnumInfo();
            String storage = extractAnnotationAttribute(ann, null) // Get default value ('value')
                    .orElse("ORDINAL"); // JPA default
            enumInfo.setStorageType(storage.replace("EnumType.", ""));
            col.setSqlType("STRING".equals(enumInfo.getStorageType()) ? "VARCHAR" : "INTEGER");

            // Try to get possible values (Requires enum class to be resolvable)
            if (resolvedType != null && resolvedType.isReferenceType()) {
                try {
                    resolvedType.asReferenceType().getTypeDeclaration()
                            .filter(ResolvedReferenceTypeDeclaration::isEnum)
                            .ifPresent(enumDecl -> {
                                List<String> values = enumDecl.asEnum().getEnumConstants().stream()
                                        .map(constant -> constant.getName())
                                        .collect(Collectors.toList());
                                enumInfo.setPossibleValues(values);
                            });
                } catch (Exception e) {
                    log.warn("Could not resolve enum constants for type {}: {}", fieldType, e.getMessage());
                }
            }
            if(enumInfo.getPossibleValues() == null) enumInfo.setPossibleValues(List.of()); // Ensure non-null list

            col.setEnumInfo(enumInfo);
        });

        return col;
    }

    private RelationshipInfo parseRelationship(FieldDeclaration field, String fieldName, String fieldType, ResolvedType resolvedFieldType) {
        RelationshipInfo rel = new RelationshipInfo();
        rel.setFieldName(fieldName);

        String targetEntityType = fieldType; // Default assumption
        boolean isCollection = false;

        // Check if the field type is a common collection type
        if (resolvedFieldType != null && resolvedFieldType.isReferenceType()) {
            String qualifiedName = resolvedFieldType.asReferenceType().getQualifiedName();
            if (qualifiedName.equals("java.util.List") || qualifiedName.equals("java.util.Set") || qualifiedName.equals("java.util.Collection")) {
                isCollection = true;
                targetEntityType = getGenericTypeArgument(field, resolvedFieldType); // Get type inside <...>
            }
        }


        // Determine relationship type based on annotation and whether it's a collection
        if (field.isAnnotationPresent("OneToOne")) {
            rel.setType("OneToOne");
            field.getAnnotationByName("OneToOne").ifPresent(ann -> parseCommonRelationshipAttributes(rel, ann));
        } else if (field.isAnnotationPresent("OneToMany")) {
            rel.setType("OneToMany");
            if(!isCollection) log.warn("@OneToMany used on non-collection field '{}'?", fieldName);
            rel.setOwningSide(false); // Usually non-owning
            field.getAnnotationByName("OneToMany").ifPresent(ann -> parseCommonRelationshipAttributes(rel, ann));
        } else if (field.isAnnotationPresent("ManyToOne")) {
            rel.setType("ManyToOne");
            if(isCollection) log.warn("@ManyToOne used on collection field '{}'?", fieldName);
            rel.setOwningSide(true); // Usually owning
            field.getAnnotationByName("ManyToOne").ifPresent(ann -> parseCommonRelationshipAttributes(rel, ann));
        } else if (field.isAnnotationPresent("ManyToMany")) {
            rel.setType("ManyToMany");
            if(!isCollection) log.warn("@ManyToMany used on non-collection field '{}'?", fieldName);
            field.getAnnotationByName("ManyToMany").ifPresent(ann -> {
                parseCommonRelationshipAttributes(rel, ann);
                if (rel.getMappedBy() != null) rel.setOwningSide(false); // mappedBy means non-owning
                else rel.setOwningSide(true); // Assume owning if no mappedBy for ManyToMany
            });
        } else {
            return null;
        }

        if (targetEntityType == null || targetEntityType.equals("UNKNOWN") || targetEntityType.equals("java.lang.Object")) {
            log.warn("Could not determine target entity type for relationship field: {}. Annotation: {}", fieldName, rel.getType());
            return null;
        }
        rel.setTargetEntityJavaClass(targetEntityType);

        // Parse JoinColumn and JoinTable
        field.getAnnotationByName("JoinColumn").ifPresent(ann -> {
            extractAnnotationAttribute(ann, "name").ifPresent(rel::setJoinColumnName);
        });
        if (rel.isOwningSide() && rel.getJoinColumnName() == null && ("ManyToOne".equals(rel.getType()) || "OneToOne".equals(rel.getType()))) {
            rel.setJoinColumnName(toSnakeCase(fieldName) + "_id"); // Default guess
        }

        field.getAnnotationByName("JoinTable").ifPresent(ann -> {
            extractAnnotationAttribute(ann, "name").ifPresent(rel::setJoinTableName);
            extractJoinColumnNameFromAnnotationExpr(ann, "joinColumns").ifPresent(rel::setJoinTableJoinColumnName);
            extractJoinColumnNameFromAnnotationExpr(ann, "inverseJoinColumns").ifPresent(rel::setJoinTableInverseJoinColumnName);
            // If JoinTable is present, it usually implies owning side for ManyToMany/OneToMany unless mappedBy is also there
            if (rel.getMappedBy() == null && ("ManyToMany".equals(rel.getType()) || "OneToMany".equals(rel.getType()))){
                rel.setOwningSide(true);
            }
        });

        return rel;
    }

    // Helper to parse common attributes like mappedBy, fetch, cascade
    private void parseCommonRelationshipAttributes(RelationshipInfo rel, AnnotationExpr ann) {
        extractAnnotationAttribute(ann, "mappedBy").ifPresent(rel::setMappedBy);
        extractAnnotationAttribute(ann, "fetch").ifPresentOrElse(
                val -> rel.setFetchType(val.replace("FetchType.", "")),
                () -> rel.setFetchType("LAZY") // Default fetch type is often LAZY (except for ToOne)
        );
        // Correct default FetchType for ToOne relationships
        if (("ManyToOne".equals(rel.getType()) || "OneToOne".equals(rel.getType())) && rel.getFetchType() == null) {
            rel.setFetchType("EAGER"); // JPA default for ToOne is EAGER
        }

        extractAnnotationAttributeList(ann, "cascade").ifPresent(rel::setCascadeTypes);
    }


    // --- Various Helper Methods (String parsing, type resolution, etc.) ---

    private String getResolvedTypeName(ResolvedType resolvedType) {
        try {
            if (resolvedType.isReferenceType()) {
                // Handle nested generics like Optional<List<String>> - get innermost non-primitive/non-generic standard type
                ResolvedReferenceType refType = resolvedType.asReferenceType();
                // Special handling for Optional
                if (refType.getQualifiedName().equals("java.util.Optional")) {
                    List<ResolvedType> typeParams = refType.getTypeParametersMap().stream().map(p -> p.b).filter(Optional::isPresent).map(Optional::get).toList();
                    if (typeParams.size() == 1) {
                        return "Optional<" + getResolvedTypeName(typeParams.get(0)) + ">"; // Indicate Optional wrapping
                    }
                }
                // Fallback to qualified name
                return refType.getQualifiedName();
            } else if (resolvedType.isPrimitive()) {
                return resolvedType.asPrimitive().name().toLowerCase();
            } else if (resolvedType.isArray()) {
                if (resolvedType.asArrayType().getComponentType().isPrimitive() &&
                        resolvedType.asArrayType().getComponentType().asPrimitive() == ResolvedType.byteType()) {
                    return "byte[]";
                }
                return getResolvedTypeName(resolvedType.asArrayType().getComponentType()) + "[]";
            }
            return resolvedType.describe();
        } catch (Exception e) {
            log.warn("Error describing resolved type: {}", e.getMessage());
            return "ERROR_RESOLVING_TYPE";
        }
    }

    private String getGenericTypeArgument(FieldDeclaration field, ResolvedType resolvedFieldType) {
        try {
            if (resolvedFieldType != null && resolvedFieldType.isReferenceType()) {
                ResolvedReferenceType refType = resolvedFieldType.asReferenceType();
                List<ResolvedType> typeArgs = refType.getTypeParametersMap().stream()
                        .map(pair -> pair.b)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .toList();
                if (!typeArgs.isEmpty()) {
                    // Assuming the first type argument is the one we want (e.g., for List<Entity>)
                    return getResolvedTypeName(typeArgs.get(0));
                } else {
                    log.warn("Could not find type arguments for generic type '{}' in field '{}'", refType.getQualifiedName(), field.getVariable(0).getNameAsString());
                }
            }
        } catch (Exception e) {
            log.warn("Error extracting generic type argument for field '{}'", field.getVariable(0).getNameAsString(), e);
        }
        return "java.lang.Object"; // Fallback
    }


    // Extracts string value from common annotation attribute expressions
    private Optional<String> extractAnnotationAttribute(AnnotationExpr annotationExpr, String attributeName) {
        if (annotationExpr == null) return Optional.empty();

        // Default attribute "value" if name is null or "value"
        final String targetAttribute = (attributeName == null || attributeName.isEmpty()) ? "value" : attributeName;

        if (annotationExpr.isSingleMemberAnnotationExpr()) {
            return "value".equals(targetAttribute) ? Optional.ofNullable(getStringValue(annotationExpr.asSingleMemberAnnotationExpr().getMemberValue())) : Optional.empty();
        }

        if (annotationExpr.isNormalAnnotationExpr()) {
            return annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> targetAttribute.equals(pair.getNameAsString()))
                    .findFirst()
                    .map(pair -> getStringValue(pair.getValue()));
        }

        return Optional.empty();
    }

    // Extracts a list of strings, typically for enum arrays like cascade={...}
    private Optional<List<String>> extractAnnotationAttributeList(AnnotationExpr annotationExpr, String attributeName) {
        if (annotationExpr == null || attributeName == null || !annotationExpr.isNormalAnnotationExpr()) {
            return Optional.empty();
        }
        Optional<Expression> valueExprOpt = annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> attributeName.equals(pair.getNameAsString()))
                .findFirst()
                .map(MemberValuePair::getValue);

        if(valueExprOpt.isPresent()) {
            Expression valueExpr = valueExprOpt.get();
            if (valueExpr.isArrayInitializerExpr()) {
                List<String> values = new ArrayList<>();
                valueExpr.asArrayInitializerExpr().getValues().forEach(valExpr -> {
                    String strVal = getStringValue(valExpr);
                    if (strVal != null) {
                        values.add(strVal.substring(strVal.lastIndexOf('.') + 1)); // Get simple name
                    }
                });
                return Optional.of(values).filter(l -> !l.isEmpty()); // Return only if not empty
            } else {
                // Handle single value case, e.g., cascade=CascadeType.ALL
                String singleVal = getStringValue(valueExpr);
                if(singleVal != null) {
                    return Optional.of(List.of(singleVal.substring(singleVal.lastIndexOf('.') + 1)));
                }
            }
        }
        return Optional.empty();
    }


    // Enhanced helper to get string representation from expressions
    private String getStringValue(Expression expr) {
        if (expr == null) return null;
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        } else if (expr.isNameExpr() || expr.isFieldAccessExpr) {
            // For enums (EnumType.STRING) or static fields
            return expr.toString();
        } else if (expr.isIntegerLiteralExpr()) {
            return expr.asIntegerLiteralExpr().getValue();
        } else if (expr.isBooleanLiteralExpr()) {
            return String.valueOf(expr.asBooleanLiteralExpr().getValue());
        } else if (expr.isLongLiteralExpr()) {
            return expr.asLongLiteralExpr().getValue();
        } else if (expr.isDoubleLiteralExpr()) {
            return expr.asDoubleLiteralExpr().getValue();
        } else if (expr.isAnnotationExpr()) { // For nested annotations like @Column in @AttributeOverride
            return expr.toString(); // Return the string representation of the annotation
        }
        log.trace("Could not extract simple string value from expression: {} (Type: {})", expr, expr.getClass().getSimpleName());
        return null;
    }

    // Extracts the 'name' attribute from a @JoinColumn annotation represented as a string or AnnotationExpr
    private Optional<String> extractJoinColumnNameFromAnnotationExpr(AnnotationExpr parentAnn, String attributeName /* "joinColumns" or "inverseJoinColumns" */) {
        return extractAnnotationAttribute(parentAnn, attributeName)
                .flatMap(annString -> {
                    // Check if it's potentially an array initializer
                    if (annString.startsWith("{") && annString.endsWith("}")) {
                        // Simple case: take the first one if it's an array
                        // More robust parsing needed for multiple @JoinColumn in array
                        String content = annString.substring(1, annString.length() - 1).trim();
                        if (content.startsWith("@JoinColumn")) {
                            annString = content.split(",")[0].trim(); // Take first element string
                        } else {
                            return Optional.empty(); // Not a JoinColumn array?
                        }
                    }

                    if (annString.startsWith("@JoinColumn")) {
                        try {
                            // Try parsing the string as an annotation
                            AnnotationExpr joinColumnAnn = StaticJavaParser.parseAnnotation(annString);
                            return extractAnnotationAttribute(joinColumnAnn, "name");
                        } catch (Exception e) {
                            log.warn("Failed to parse @JoinColumn string: {}", annString, e);
                            // Fallback: Very basic string parsing as before (less reliable)
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
                    return Optional.empty(); // Not a @JoinColumn string
                });
    }


    // Parses @AttributeOverride annotations from @AttributeOverrides
    private Map<String, String> getAttributeOverrides(AnnotationExpr overridesAnnotation) {
        Map<String, String> overrides = new HashMap<>();
        if (overridesAnnotation == null || !overridesAnnotation.isAnnotationExpr()) return overrides;

        Expression valueExpr = null;
        if (overridesAnnotation.isNormalAnnotationExpr()) {
            valueExpr = overridesAnnotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString())).findFirst().map(MemberValuePair::getValue).orElse(null);
        } else if (overridesAnnotation.isSingleMemberAnnotationExpr()){
            valueExpr = overridesAnnotation.asSingleMemberAnnotationExpr().getMemberValue();
        } else { return overrides; }


        if (valueExpr != null && valueExpr.isArrayInitializerExpr()) {
            valueExpr.asArrayInitializerExpr().getValues().forEach(overrideNode -> {
                if (overrideNode instanceof AnnotationExpr overrideAnn && overrideAnn.getNameAsString().equals("AttributeOverride")) {
                    Optional<String> nameOpt = extractAnnotationAttribute(overrideAnn, "name");
                    Optional<String> columnOpt = extractAnnotationAttribute(overrideAnn, "column")
                            .flatMap(colAnnStr -> {
                                try { return extractAnnotationAttribute(StaticJavaParser.parseAnnotation(colAnnStr), "name");}
                                catch (Exception e) { log.warn("Failed to parse @Column string in @AttributeOverride: {}", colAnnStr); return Optional.empty();}
                            });
                    if (nameOpt.isPresent() && columnOpt.isPresent()) {
                        overrides.put(nameOpt.get(), columnOpt.get());
                    }
                }
            });
        } else if (valueExpr instanceof AnnotationExpr overrideAnn && overrideAnn.getNameAsString().equals("AttributeOverride")){
            // Handle single @AttributeOverride case (though usually within @AttributeOverrides array)
            Optional<String> nameOpt = extractAnnotationAttribute(overrideAnn, "name");
            Optional<String> columnOpt = extractAnnotationAttribute(overrideAnn, "column")
                    .flatMap(colAnnStr -> {
                        try { return extractAnnotationAttribute(StaticJavaParser.parseAnnotation(colAnnStr), "name");}
                        catch (Exception e) { log.warn("Failed to parse @Column string in @AttributeOverride: {}", colAnnStr); return Optional.empty();}
                    });
            if (nameOpt.isPresent() && columnOpt.isPresent()) {
                overrides.put(nameOpt.get(), columnOpt.get());
            }
        }
        return overrides;
    }

    // Check nullable attribute within @AttributeOverride -> @Column
    private Boolean isOverriddenNullable(AnnotationExpr overridesAnnotation, String fieldName) {
        if (overridesAnnotation == null || !overridesAnnotation.isAnnotationExpr()) return null;

        Expression valueExpr = null;
        if(overridesAnnotation.isNormalAnnotationExpr()) {
            valueExpr = overridesAnnotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString())).findFirst().map(MemberValuePair::getValue).orElse(null);
        } else if (overridesAnnotation.isSingleMemberAnnotationExpr()){
            valueExpr = overridesAnnotation.asSingleMemberAnnotationExpr().getMemberValue();
        } else { return null; }


        if (valueExpr != null && valueExpr.isArrayInitializerExpr()) {
            for(Node overrideNode : valueExpr.asArrayInitializerExpr().getValues()){
                if (overrideNode instanceof AnnotationExpr overrideAnn && overrideAnn.getNameAsString().equals("AttributeOverride")) {
                    Optional<String> nameOpt = extractAnnotationAttribute(overrideAnn, "name");
                    if(nameOpt.isPresent() && nameOpt.get().equals(fieldName)) {
                        return extractAnnotationAttribute(overrideAnn, "column")
                                .flatMap(colAnnStr -> {
                                    try { return extractAnnotationAttribute(StaticJavaParser.parseAnnotation(colAnnStr), "nullable");}
                                    catch (Exception e) { return Optional.empty();}
                                })
                                .map(Boolean::parseBoolean).orElse(null); // Return parsed boolean or null if not found/parsable
                    }
                }
            }
        } else if (valueExpr instanceof AnnotationExpr overrideAnn && overrideAnn.getNameAsString().equals("AttributeOverride")){
            Optional<String> nameOpt = extractAnnotationAttribute(overrideAnn, "name");
            if(nameOpt.isPresent() && nameOpt.get().equals(fieldName)) {
                return extractAnnotationAttribute(overrideAnn, "column")
                        .flatMap(colAnnStr -> {
                            try { return extractAnnotationAttribute(StaticJavaParser.parseAnnotation(colAnnStr), "nullable");}
                            catch (Exception e) { return Optional.empty();}
                        })
                        .map(Boolean::parseBoolean).orElse(null);
            }
        }
        return null; // No override found for this field
    }


    // --- Utility Methods ---

    private EmbeddableInfo findEmbeddableDefinition(String qualifiedClassName) {
        return embeddables.stream()
                .filter(e -> qualifiedClassName.equals(e.getJavaClassName()))
                .findFirst()
                .orElse(null);
    }

    private String toSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return "";
        // Improved snake case: Handles acronyms like "URL" -> "url" and leading uppercase
        return camelCase.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
                .toLowerCase();
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
        map.put("Float", "REAL"); // Or FLOAT
        map.put("float", "REAL");
        map.put("Boolean", "BOOLEAN");
        map.put("boolean", "BOOLEAN");
        map.put("LocalDate", "DATE");
        map.put("LocalDateTime", "TIMESTAMP");
        map.put("ZonedDateTime", "TIMESTAMP WITH TIME ZONE");
        map.put("OffsetDateTime", "TIMESTAMP WITH TIME ZONE");
        map.put("Instant", "TIMESTAMP WITH TIME ZONE");
        map.put("Date", "TIMESTAMP"); // java.util.Date
        map.put("Timestamp", "TIMESTAMP"); // java.sql.Timestamp
        map.put("Time", "TIME"); // java.sql.Time
        map.put("BigDecimal", "NUMERIC");
        map.put("BigInteger", "NUMERIC");
        map.put("byte[]", "BYTEA");
        map.put("Byte[]", "BYTEA");
        map.put("UUID", "UUID");
        map.put("Character", "CHAR(1)");
        map.put("char", "CHAR(1)");
        // Add other common types if needed
        return Collections.unmodifiableMap(map);
    }

    private String guessSqlType(String javaType) {
        String simpleJavaType = javaType.substring(javaType.lastIndexOf('.') + 1);
        // Handle Optional<Type> by guessing based on inner type
        if (simpleJavaType.startsWith("Optional<") && simpleJavaType.endsWith(">")) {
            String innerType = simpleJavaType.substring(9, simpleJavaType.length() - 1);
            return guessSqlType(innerType); // Recursive call for inner type
        }
        return JAVA_TO_SQL_TYPE_MAP.getOrDefault(simpleJavaType, "VARCHAR"); // Default fallback
    }

    // --- Deep Copy Helpers --- (Keep these as previously defined) ---
    private ColumnInfo deepCopyColumnInfo(ColumnInfo original) {
        ColumnInfo copy = new ColumnInfo();
        copy.setFieldName(original.getFieldName());
        copy.setColumnName(original.getColumnName());
        copy.setJavaType(original.getJavaType());
        copy.setSqlType(original.getSqlType());
        copy.setPrimaryKey(original.isPrimaryKey());
        copy.setGenerationStrategy(original.getGenerationStrategy());
        copy.setNullable(original.getNullable()); // Copy Boolean
        copy.setUnique(original.getUnique());     // Copy Boolean
        copy.setLength(original.getLength());
        copy.setPrecision(original.getPrecision());
        copy.setScale(original.getScale());
        copy.setIsEnum(original.getIsEnum());     // Copy Boolean
        if (original.getEnumInfo() != null) {
            EnumInfo eiCopy = new EnumInfo();
            eiCopy.setStorageType(original.getEnumInfo().getStorageType());
            eiCopy.setPossibleValues(original.getEnumInfo().getPossibleValues() != null ? new ArrayList<>(original.getEnumInfo().getPossibleValues()) : new ArrayList<>());
            copy.setEnumInfo(eiCopy);
        }
        copy.setIsEmbeddedAttribute(original.getIsEmbeddedAttribute()); // Copy Boolean
        copy.setEmbeddedFromFieldName(original.getEmbeddedFromFieldName());
        copy.setOriginalEmbeddableFieldName(original.getOriginalEmbeddableFieldName());
        copy.setInherited(original.getInherited());         // Copy Boolean
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
        copy.setInherited(original.getInherited()); // Copy Boolean
        copy.setInheritedFromClass(original.getInheritedFromClass());
        return copy;
    }

    // Helper to add column only if not already present by field name, and mark inheritance
    private void addIfNotPresent(List<ColumnInfo> list, ColumnInfo itemToAdd, boolean inherited, String inheritedFrom) {
        if (list.stream().noneMatch(existing -> existing.getFieldName().equals(itemToAdd.getFieldName()))) {
            itemToAdd.setInherited(inherited);
            if(inherited) itemToAdd.setInheritedFromClass(inheritedFrom);
            list.add(itemToAdd);
        } else {
            log.trace("Skipping adding inherited/duplicate column field: {}", itemToAdd.getFieldName());
        }
    }
    // Overload for non-inherited cases
    private void addIfNotPresent(List<ColumnInfo> list, ColumnInfo itemToAdd) {
        addIfNotPresent(list, itemToAdd, false, null);
    }


    // Helper to add relationship only if not already present by field name, and mark inheritance
    private void addIfNotPresent(List<RelationshipInfo> list, RelationshipInfo itemToAdd, boolean inherited, String inheritedFrom) {
        if (list.stream().noneMatch(existing -> existing.getFieldName().equals(itemToAdd.getFieldName()))) {
            itemToAdd.setInherited(inherited);
            if(inherited) itemToAdd.setInheritedFromClass(inheritedFrom);
            list.add(itemToAdd);
        } else {
            log.trace("Skipping adding inherited/duplicate relationship field: {}", itemToAdd.getFieldName());
        }
    }


    // Internal helper class to store info about parsed MappedSuperclasses temporarily
    @Data
    static class MappedSuperclassInfo {
        private String javaClassName;
        private List<ColumnInfo> columns = new ArrayList<>();
        private List<RelationshipInfo> relationships = new ArrayList<>();
    }
}