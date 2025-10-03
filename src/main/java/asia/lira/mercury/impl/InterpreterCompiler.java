package asia.lira.mercury.impl;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import javassist.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * A compiler to compile the dynamic CommandDispatcher to a static Interpreter.
 *
 * @param <S>
 */
@SuppressWarnings("DuplicatedCode")
public class InterpreterCompiler<S> {
    /**
     * the default requirement which always returns true.
     * Bytecode: com/mojang/brigadier/builder/ArgumentBuilder.lambda$new$0
     * Runtime: com.mojang.brigadier.builder.ArgumentBuilder$$Lambda/##memory_address##
     *
     * @see ArgumentBuilder#requirement
     */
    @SuppressWarnings({"JavadocReference", "unchecked", "rawtypes"})
    private static final Class<? extends Predicate<?>> DEFAULT_REQUIREMENT = (Class<? extends Predicate<?>>) new ArgumentBuilder() {

        @Override
        protected ArgumentBuilder getThis() {
            return null;
        }

        @Override
        public CommandNode build() {
            return null;
        }
    }.getRequirement().getClass();
    private static final CustomClassLoader LOADER = new CustomClassLoader(Thread.currentThread().getContextClassLoader());
    private static final int NO_MATCH = -1948823413;

    private final CommandDispatcher<S> dispatcher;
    private int fieldCounter = 0;
    private int methodCounter = 0;

    public InterpreterCompiler(CommandDispatcher<S> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @SuppressWarnings("unchecked")
    public IInterpreter<S> compile() throws CannotCompileException, NotFoundException, IOException,
            NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException, NoSuchFieldException {
        ClassPool pool = ClassPool.getDefault();
        pool.appendClassPath(new LoaderClassPath(Thread.currentThread().getContextClassLoader()));
        CtClass cc = pool.makeClass("asia.lira.mercury.generated.Interpreter");
        cc.addInterface(pool.get(IInterpreter.class.getName()));

        List<Object> fields = new ObjectArrayList<>();
        CtMethod method = CtNewMethod.make(
                Modifier.PUBLIC | Modifier.FINAL,
                CtClass.intType,
                "execute",
                new CtClass[]{pool.get(Object.class.getName()), pool.get("java.lang.String"), pool.get("java.lang.String[]")},
                new CtClass[]{},
                compileCode(cc, pool, fields),
                cc
        );
        cc.addMethod(method);
        cc.addConstructor(CtNewConstructor.defaultConstructor(cc));
        cc.writeFile();

        Class<IInterpreter<S>> clazz = (Class<IInterpreter<S>>) LOADER.defineClass(cc.getName(), cc.toBytecode());
        for (int i = 0, fieldsSize = fields.size(); i < fieldsSize; i++) {
            Object value = fields.get(i);
            if (value != null) {
                clazz.getField("field_" + i).set(null, value);
            }
        }
        return clazz.getDeclaredConstructor().newInstance();
    }

    private @NotNull String compileCode(CtClass cc, ClassPool pool, List<Object> fields) throws NotFoundException, CannotCompileException {
        StringBuilder builder = new StringBuilder(1024);
        builder.append('{');

        builder.append("java.lang.String cur;");
        builder.append("com.mojang.brigadier.context.CommandContextBuilder ctxBuilder = new com.mojang.brigadier.context.CommandContextBuilder(null, $1, null, 0);");
        builder.append("int retVal;");
        compileNext(cc, pool, fields, builder, dispatcher.getRoot(), 0);

        builder.append("return ");
        builder.append(NO_MATCH);
        builder.append(';');
        builder.append('}');
        return builder.toString();
    }

    private void compileNext(CtClass cc, ClassPool pool, List<Object> fields, @NotNull StringBuilder builder,
                             @NotNull CommandNode<S> node, int depth) throws NotFoundException, CannotCompileException {
        Collection<CommandNode<S>> children = node.getChildren();
        if (children.isEmpty()) return;

        StringBuilder litBuilder = null;
        StringBuilder argBuilder = null;
        for (CommandNode<S> child : children) {
            if (child instanceof ArgumentCommandNode<S, ?> argNode) {
                if (argBuilder == null) {
                    argBuilder = new StringBuilder(256);
                    argBuilder.append("do {");
                }

                // requirements
                Predicate<S> requirement = argNode.getRequirement();
                if (requirement.getClass() != DEFAULT_REQUIREMENT) {
                    argBuilder.append("if (!");
                    argBuilder.append(makeField(cc, fields, pool.get("java.util.function.Predicate"), requirement));
                    argBuilder.append(".test($1)) break;");
                }

                argBuilder.append("try {");

                // parse
                argBuilder.append(makeField(cc, fields, pool.get("com.mojang.brigadier.tree.ArgumentCommandNode"), argNode));
                argBuilder.append(".parse(new com.mojang.brigadier.StringReader(cur = $3[");
                argBuilder.append(depth);
                argBuilder.append("]), ctxBuilder);");

                // children
                compileWithNewMethod(cc, pool, fields, argBuilder, child, depth + 1);

                // execute
                Command<S> command = child.getCommand();
                if (command != null) {
                    argBuilder.append("return ");
                    argBuilder.append(makeField(cc, fields, pool.get("com.mojang.brigadier.Command"), command));
                    argBuilder.append(".run(ctxBuilder.build($2));");
                } else {
                    argBuilder.append("break;");
                }

                argBuilder.append("} catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {}");
                continue;
            }

            if (litBuilder == null) {
                litBuilder = new StringBuilder(256);
                litBuilder.append("switch ((cur = $3[");
                litBuilder.append(depth);
                litBuilder.append("]).hashCode()) {");
            }

            litBuilder.append("case ");
            litBuilder.append(child.getName().hashCode());
            litBuilder.append(':');

            litBuilder.append("if (!cur.equals(\"");
            litBuilder.append(child.getName().replace("\\", "\\\\").replace("\"", "\\\""));
            litBuilder.append("\")) break;");

            // requirements
            Predicate<S> requirement = child.getRequirement();
            if (requirement.getClass() != DEFAULT_REQUIREMENT) {
                litBuilder.append("if (!");
                litBuilder.append(makeField(cc, fields, pool.get("java.util.function.Predicate"), requirement));
                litBuilder.append(".test($1)) break;");
            }

            // children
            compileWithNewMethod(cc, pool, fields, litBuilder, child, depth + 1);

            // execute
            Command<S> command = child.getCommand();
            if (command != null) {
                litBuilder.append("return ");
                litBuilder.append(makeField(cc, fields, pool.get("com.mojang.brigadier.Command"), command));
                litBuilder.append(".run(ctxBuilder.build($2));");
            } else {
                litBuilder.append("break;");
            }
        }

        if (litBuilder != null) {
            litBuilder.append("}");
            builder.append(litBuilder);
        }
        if (argBuilder != null) {
            argBuilder.append("} while (false);");
            builder.append(argBuilder);
        }
    }

    private @NotNull String makeField(CtClass cc, @NotNull List<Object> fields, CtClass type, Object value) throws CannotCompileException {
        String fieldName = "field_" + fieldCounter++;
        CtField field = new CtField(type, fieldName, cc);
        field.setModifiers(Modifier.PUBLIC | Modifier.STATIC | 0x1000);
        cc.addField(field);
        fields.add(value);
        assert fields.size() == fieldCounter;
        return fieldName;
    }

    private void compileWithNewMethod(CtClass cc, @NotNull ClassPool pool, List<Object> fields, @NotNull StringBuilder builder,
                                      @NotNull CommandNode<S> node, int depth) throws NotFoundException, CannotCompileException {
        if (node.getChildren().isEmpty()) return;

        String methodName = "method_" + methodCounter++;

        StringBuilder nextBuilder = new StringBuilder(1024);
        nextBuilder.append('{');
        nextBuilder.append("java.lang.String cur = $4;");
        nextBuilder.append("com.mojang.brigadier.context.CommandContextBuilder ctxBuilder = $5;");
        nextBuilder.append("int retVal;");
        compileNext(cc, pool, fields, nextBuilder, node, depth);
        nextBuilder.append("return ");
        nextBuilder.append(NO_MATCH);
        nextBuilder.append(';');
        nextBuilder.append('}');
        CtMethod method = CtNewMethod.make(
                Modifier.PUBLIC | Modifier.STATIC,
                CtClass.intType,
                methodName,
                new CtClass[]{
                        pool.get(Object.class.getName()), pool.get("java.lang.String"), pool.get("java.lang.String[]"),
                        pool.get("java.lang.String"), pool.get("com.mojang.brigadier.context.CommandContextBuilder")
                },
                new CtClass[]{},
                nextBuilder.toString(),
                cc
        );
        cc.addMethod(method);

        builder.append("if ($3.length > ");
        builder.append(depth);
        builder.append("&& (retVal =");
        builder.append(methodName);
        builder.append("($1, $2, $3, cur, ctxBuilder)) != ");
        builder.append(NO_MATCH);
        builder.append(") return retVal;");
    }

    public static final class CustomClassLoader extends ClassLoader {
        public CustomClassLoader(ClassLoader parent) {
            super(parent);
        }

        public Class<?> defineClass(String name, byte[] b) {
            return super.defineClass(name, b, 0, b.length);
        }
    }

}
