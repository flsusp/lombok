/*
 * Copyright (C) 2009-2014 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.experimental.Delegate;
import lombok.experimental.Hasser;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.JavacTreeMaker.TreeTag;
import lombok.javac.handlers.JavacHandlerUtil.*;
import org.mangosdk.spi.ProviderFor;

import java.util.Collection;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.Javac.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.toHasserName;

/**
 * Handles the {@code lombok.Hasser} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleHasser extends JavacAnnotationHandler<Hasser> {

	@Override
	public void handle(AnnotationValues<Hasser> annotation, JCAnnotation ast, JavacNode annotationNode) {
		handleFlagUsage(annotationNode, ConfigurationKeys.HASSER_FLAG_USAGE, "@Hasser");

		Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
		deleteAnnotationIfNeccessary(annotationNode, Hasser.class);
		deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
		JavacNode node = annotationNode.up();
		Hasser annotationInstance = annotation.getInstance();
		AccessLevel level = annotationInstance.value();

		if (node == null) return;

		switch (node.getKind()) {
			case FIELD:
				createHasserForFields(level, fields, annotationNode, true);
				break;
			case TYPE:
				generateHasserForType(node, annotationNode, level, false);
				break;
		}
	}

	private void generateHasserForType(JavacNode typeNode, JavacNode errorNode, AccessLevel level, boolean checkForTypeLevelHasser) {
		if (checkForTypeLevelHasser) {
			if (hasAnnotation(Hasser.class, typeNode)) {
				//The annotation will make it happen, so we can skip it.
				return;
			}
		}

		JCClassDecl typeDecl = null;
		if (typeNode.get() instanceof JCClassDecl) typeDecl = (JCClassDecl) typeNode.get();
		long modifiers = typeDecl == null ? 0 : typeDecl.mods.flags;
		boolean notAClass = (modifiers & (Flags.INTERFACE | Flags.ANNOTATION)) != 0;

		if (typeDecl == null || notAClass) {
			errorNode.addError("@Hasser is only supported on a class, an enum, or a field.");
			return;
		}

		for (JavacNode field : typeNode.down()) {
			if (fieldQualifiesForHasserGeneration(field)) generateHasserForField(field, errorNode.get(), level, false);
		}
	}

	private boolean fieldQualifiesForHasserGeneration(JavacNode field) {
		if (field.getKind() != Kind.FIELD) return false;
		JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
		//Skip fields that start with $
		if (fieldDecl.name.toString().startsWith("$")) return false;
		//Skip static fields.
		if ((fieldDecl.mods.flags & Flags.STATIC) != 0) return false;
		return true;
	}

	/**
	 * Generates a hasser on the stated field.
	 * <p>
	 * The difference between this call and the handle method is as follows:
	 * <p>
	 * If there is a {@code lombok.Hasser} annotation on the field, it is used and the
	 * same rules apply (e.g. warning if the method already exists, stated access level applies).
	 * If not, the hasser is still generated if it isn't already there, though there will not
	 * be a warning if its already there. The default access level is used.
	 *
	 * @param fieldNode The node representing the field you want a hasser for.
	 * @param pos       The node responsible for generating the hasser (the {@code @Hasser} annotation).
	 */
	private void generateHasserForField(JavacNode fieldNode, DiagnosticPosition pos, AccessLevel level, boolean lazy) {
		if (hasAnnotation(Hasser.class, fieldNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}
		createHasserForField(level, fieldNode, fieldNode, false);
	}

	private void createHasserForFields(AccessLevel level, Collection<JavacNode> fieldNodes, JavacNode errorNode, boolean whineIfExists) {
		for (JavacNode fieldNode : fieldNodes) {
			createHasserForField(level, fieldNode, errorNode, whineIfExists);
		}
	}

	private void createHasserForField(AccessLevel level,
									  JavacNode fieldNode, JavacNode source, boolean whineIfExists) {
		if (fieldNode.getKind() != Kind.FIELD) {
			source.addError("@Hasser is only supported on a class or a field.");
			return;
		}

		JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();

		String methodName = toHasserName(fieldNode);
		if (methodName == null) {
			source.addWarning("Not generating hasser for this field: It does not fit your @Accessors prefix list.");
			return;
		}

		switch (methodExists(methodName, fieldNode, false, 0)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					source.addWarning(String.format("Not generating %s(): A method with that name already exists", methodName));
				}
				return;
		}

		long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);

		injectMethod(fieldNode.up(), createHasser(access, fieldNode, fieldNode.getTreeMaker(), source.get()));
	}

	private JCMethodDecl createHasser(long access, JavacNode field, JavacTreeMaker treeMaker, JCTree source) {
		JCVariableDecl fieldNode = (JCVariableDecl) field.get();

		JCExpression methodType = treeMaker.TypeIdent(CTC_BOOLEAN);
		Name methodName = field.toName(toHasserName(field));

		JCStatement statement = createSimpleHasserBody(treeMaker, field);
		JCTree toClearOfMarkers = null;

		JCBlock methodBody = treeMaker.Block(0, List.of(statement));

		List<JCTypeParameter> methodGenericParams = List.nil();
		List<JCVariableDecl> parameters = List.nil();
		List<JCExpression> throwsClauses = List.nil();
		JCExpression annotationMethodDefaultValue = null;

		List<JCAnnotation> delegates = findDelegatesAndRemoveFromField(field);

		List<JCAnnotation> annsOnMethod = List.nil();
		if (isFieldDeprecated(field)) {
			annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));
		}

		JCMethodDecl decl = recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName, methodType,
				methodGenericParams, parameters, throwsClauses, methodBody, annotationMethodDefaultValue), source, field.getContext());

		if (toClearOfMarkers != null) recursiveSetGeneratedBy(toClearOfMarkers, null, null);
		decl.mods.annotations = decl.mods.annotations.appendList(delegates);

		copyJavadoc(field, decl, CopyJavadoc.HASSER);
		return decl;
	}

	private static List<JCAnnotation> findDelegatesAndRemoveFromField(JavacNode field) {
		JCVariableDecl fieldNode = (JCVariableDecl) field.get();

		List<JCAnnotation> delegates = List.nil();
		for (JCAnnotation annotation : fieldNode.mods.annotations) {
			if (typeMatches(Delegate.class, field, annotation.annotationType)) {
				delegates = delegates.append(annotation);
			}
		}

		if (!delegates.isEmpty()) {
			ListBuffer<JCAnnotation> withoutDelegates = new ListBuffer<JCAnnotation>();
			for (JCAnnotation annotation : fieldNode.mods.annotations) {
				if (!delegates.contains(annotation)) {
					withoutDelegates.append(annotation);
				}
			}
			fieldNode.mods.annotations = withoutDelegates.toList();
			field.rebuild();
		}
		return delegates;
	}

	private JCStatement createSimpleHasserBody(JavacTreeMaker maker, JavacNode field) {
		boolean lookForGetter = lookForGetter(field, FieldAccess.GETTER);

		GetterMethod getter = lookForGetter ? findGetter(field) : null;

		if (getter != null) {
			return maker.Return(
					maker.Binary(TreeTag.treeTag("NE"),
							maker.Apply(List.<JCExpression>nil(),
									maker.Select(maker.Ident(field.toName("this")), getter.name), List.<JCExpression>nil()),
							maker.Ident(field.toName("null")))
			);
		} else {
			return maker.Return(
					maker.Binary(TreeTag.treeTag("NE"), maker.Select(maker.Ident(field.toName("this")), field.toName(field.getName())),
							maker.Ident(field.toName("null")))
			);
		}
	}
}
