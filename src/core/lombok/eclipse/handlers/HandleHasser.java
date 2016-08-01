package lombok.eclipse.handlers;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.agent.PatchDelegate;
import lombok.experimental.Delegate;
import lombok.experimental.Hasser;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.BinaryExpression;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NullLiteral;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.mangosdk.spi.ProviderFor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.eclipse.Eclipse.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.toHasserName;

/**
 * Handles the {@code lombok.experimental.Hasser} annotation for eclipse.
 */
@ProviderFor(EclipseAnnotationHandler.class)
public class HandleHasser extends EclipseAnnotationHandler<Hasser> {

	private static final Annotation[] EMPTY_ANNOTATIONS_ARRAY = new Annotation[0];

	public void handle(AnnotationValues<Hasser> annotation, Annotation ast, EclipseNode annotationNode) {
		handleFlagUsage(annotationNode, ConfigurationKeys.HASSER_FLAG_USAGE, "@Hasser");

		EclipseNode node = annotationNode.up();
		Hasser annotationInstance = annotation.getInstance();
		AccessLevel level = annotationInstance.value();

		if (node == null) return;

		switch (node.getKind()) {
			case FIELD:
				createHasserForFields(level, annotationNode.upFromAnnotationToFields(), annotationNode, annotationNode.get(), true);
				break;
			case TYPE:
				generateHasserForType(node, annotationNode, level, false);
				break;
		}
	}

	public boolean generateHasserForType(EclipseNode typeNode, EclipseNode pos, AccessLevel level, boolean checkForTypeLevelAnnotation) {
		if (checkForTypeLevelAnnotation) {
			if (hasAnnotation(Hasser.class, typeNode)) {
				// The annotation will make it happen, so we can skip it.
				return true;
			}
		}

		TypeDeclaration typeDecl = null;
		if (typeNode.get() instanceof TypeDeclaration) typeDecl = (TypeDeclaration) typeNode.get();
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers &
				(ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation)) != 0;

		if (typeDecl == null || notAClass) {
			pos.addError("@Hasser is only supported on a class, an enum, or a field.");
			return false;
		}

		for (EclipseNode field : typeNode.down()) {
			if (fieldQualifiesForHasserGeneration(field)) generateHasserForField(field, pos.get(), level);
		}
		return true;
	}

	/**
	 * Generates a hasser on the stated field.
	 * <p>
	 * Used by {@link HandleData}.
	 * <p>
	 * The difference between this call and the handle method is as follows:
	 * <p>
	 * If there is a {@code lombok.experimental.Hasser} annotation on the field, it is used and the
	 * same rules apply (e.g. warning if the method already exists, stated access level applies).
	 * If not, the hasser is still generated if it isn't already there, though there will not
	 * be a warning if its already there. The default access level is used.
	 */
	public void generateHasserForField(EclipseNode fieldNode, ASTNode pos, AccessLevel level) {
		if (hasAnnotation(Hasser.class, fieldNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}

		createHasserForField(level, fieldNode, fieldNode, pos, false);
	}

	public boolean fieldQualifiesForHasserGeneration(EclipseNode field) {
		if (field.getKind() != Kind.FIELD) return false;
		FieldDeclaration fieldDecl = (FieldDeclaration) field.get();
		return filterField(fieldDecl);
	}

	public void createHasserForFields(AccessLevel level, Collection<EclipseNode> fieldNodes, EclipseNode errorNode, ASTNode source, boolean whineIfExists) {
		for (EclipseNode fieldNode : fieldNodes) {
			createHasserForField(level, fieldNode, errorNode, source, whineIfExists);
		}
	}

	public void createHasserForField(AccessLevel level,
									 EclipseNode fieldNode, EclipseNode errorNode, ASTNode source, boolean whineIfExists) {
		if (fieldNode.getKind() != Kind.FIELD) {
			errorNode.addError("@Hasser is only supported on a class or a field.");
			return;
		}

		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		if (isPrimitive(field.type)) {
			errorNode.addError("@Hasser is only supported on non primitive fields.");
			return;
		}

		String hasserName = toHasserName(fieldNode);

		if (hasserName == null) {
			errorNode.addWarning("Not generating hasser for this field: It does not fit your @Accessors prefix list.");
			return;
		}

		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);

		switch (methodExists(hasserName, fieldNode, false, 0)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					errorNode.addWarning(
							String.format("Not generating %s(): A method with that name already exists", hasserName));
				}
				return;
			default:
		}

		MethodDeclaration method = createHasser((TypeDeclaration) fieldNode.up().get(), fieldNode, hasserName, modifier, source);

		injectMethod(fieldNode.up(), method);
	}

	public MethodDeclaration createHasser(TypeDeclaration parent, EclipseNode fieldNode, String name, int modifier, ASTNode source) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();

		TypeReference returnType = TypeReference.baseTypeReference(TypeIds.T_boolean, 0);

		Statement[] statements = createHasserBody(source, fieldNode);

		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = modifier;
		method.returnType = returnType;
		method.annotations = null;
		method.arguments = null;
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		method.statements = statements;

		/* Generate annotations that must be put on the generated method, and attach them. */
		{
			Annotation[] deprecated = null;
			if (isFieldDeprecated(fieldNode)) {
				deprecated = new Annotation[]{generateDeprecatedAnnotation(source)};
			}

			method.annotations = copyAnnotations(source,
					findAnnotations(field, NON_NULL_PATTERN),
					findAnnotations(field, NULLABLE_PATTERN),
					findDelegatesAndMarkAsHandled(fieldNode),
					deprecated);
		}

		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		return method;
	}

	public Statement[] createHasserBody(ASTNode source, EclipseNode fieldNode) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		Expression fieldRef = createFieldAccessor(fieldNode, FieldAccess.GETTER, source);
		EqualExpression hasExpression = new EqualExpression(
				fieldRef, new NullLiteral(source.sourceStart, source.sourceEnd),
				BinaryExpression.NOT_EQUAL);
		Statement returnStatement = new ReturnStatement(hasExpression, field.sourceStart, field.sourceEnd);
		return new Statement[]{returnStatement};
	}

	public static Annotation[] findDelegatesAndMarkAsHandled(EclipseNode fieldNode) {
		List<Annotation> delegates = new ArrayList<Annotation>();
		for (EclipseNode child : fieldNode.down()) {
			if (annotationTypeMatches(Delegate.class, child)) {
				Annotation delegate = (Annotation) child.get();
				PatchDelegate.markHandled(delegate);
				delegates.add(delegate);
			}
		}
		return delegates.toArray(EMPTY_ANNOTATIONS_ARRAY);
	}
}
