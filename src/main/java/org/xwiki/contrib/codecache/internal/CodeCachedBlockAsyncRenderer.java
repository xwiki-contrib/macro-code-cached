/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.codecache.internal;

import java.util.List;

import javax.inject.Inject;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.rendering.RenderingException;
import org.xwiki.rendering.async.AsyncContext;
import org.xwiki.rendering.async.internal.block.AbstractBlockAsyncRenderer;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.CompositeBlock;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.match.MetadataBlockMatcher;
import org.xwiki.rendering.listener.MetaData;
import org.xwiki.rendering.macro.MacroExecutionException;
import org.xwiki.rendering.macro.code.CodeMacroParameters;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.transformation.MacroTransformationContext;

@Component(roles = CodeCachedBlockAsyncRenderer.class)
public class CodeCachedBlockAsyncRenderer extends AbstractBlockAsyncRenderer
{
    @Inject
    private DocumentReferenceResolver<String> resolver;

    @Inject
    private AsyncContext asyncContext;

    private List<String> id;

    private boolean inline;

    private Syntax targetSyntax;

    private CodeCachedMacro codeMacro;

    private CodeMacroParameters parameters;

    private String content;

    private MacroTransformationContext context;

    private DocumentReference sourceReference;

    private boolean async;

    void initialize(CodeCachedMacro codeMacro, CodeMacroParameters parameters,
        String content, MacroTransformationContext context, boolean async)
    {
        this.codeMacro = codeMacro;
        this.parameters = parameters;
        this.content = content;
        this.context = context;
        this.async = async;

        this.inline = context.isInline();
        this.targetSyntax = context.getTransformationContext().getTargetSyntax();

        String source = getCurrentSource(context);
        if (source != null) {
            this.sourceReference = this.resolver.resolve(source);
        }

        // Find index of the macro in the XDOM
        long index = context.getXDOM().indexOf(context.getCurrentMacroBlock());
        this.id = createId("rendering", "macro", "code", source, index);
    }

    @Override
    protected Block execute(boolean async, boolean cached) throws RenderingException
    {
        if (this.sourceReference != null) {
            // Invalidate the cache when the document containing the macro call is modified
            this.asyncContext.useEntity(this.sourceReference);
        }

        Exception lastException = null;
        // Note: method only called when the content is not cached.
        for (int i = 0; i < 3; i++) {
            try {
                return new CompositeBlock(this.codeMacro.executeCodeMacro(this.parameters, this.content, this.context));
            } catch (MacroExecutionException e) {
                // Seems that Pygments is not fully thread safe so retrying if an exception occurs to avoid:
                //   Caused by: javax.script.ScriptException: RuntimeError: dictionary changed size during iteration in
                //     <script> at line number 35
                //   at org.python.jsr223.PyScriptEngine.scriptException(PyScriptEngine.java:222)
                //   at org.python.jsr223.PyScriptEngine.eval(PyScriptEngine.java:59)
                //   at org.python.jsr223.PyScriptEngine.eval(PyScriptEngine.java:31)
                //   at org.xwiki.rendering.internal.parser.pygments.PygmentsParser.highlight(PygmentsParser.java:208)
                //   at org.xwiki.rendering.internal.parser.pygments.PygmentsParser.highlight(PygmentsParser.java:174)
                //   ... 12 more
                //   Caused by: Traceback (most recent call last):
                //     File "<script>", line 35, in <module>
                //     File "<script>", line 35, in <module>
                //     File "__pyclasspath__/pygments/lexers/__init__.py", line 312, in guess_lexer
                //     File "__pyclasspath__/pygments/lexer.py", line 573, in __call__
                //     RuntimeError: dictionary changed size during iteration
                lastException = e;
            }
        }
        throw new RenderingException("Failed to execute the code macro asynchronously", lastException);
    }

    @Override
    public boolean isInline()
    {
        return this.inline;
    }

    @Override
    public Syntax getTargetSyntax()
    {
        return this.targetSyntax;
    }

    @Override
    public List<String> getId()
    {
        return this.id;
    }

    @Override
    public boolean isAsyncAllowed()
    {
        return this.async;
    }

    @Override
    public boolean isCacheAllowed()
    {
        return true;
    }

    private String getCurrentSource(MacroTransformationContext context)
    {
        String currentSource = null;

        if (context != null) {
            currentSource =
                context.getTransformationContext() != null ? context.getTransformationContext().getId() : null;

            MacroBlock currentMacroBlock = context.getCurrentMacroBlock();

            if (currentMacroBlock != null) {
                MetaDataBlock metaDataBlock =
                    currentMacroBlock.getFirstBlock(new MetadataBlockMatcher(MetaData.SOURCE),
                        Block.Axes.ANCESTOR_OR_SELF);

                if (metaDataBlock != null) {
                    currentSource = (String) metaDataBlock.getMetaData().getMetaData(MetaData.SOURCE);
                }
            }
        }

        return currentSource;
    }
}
