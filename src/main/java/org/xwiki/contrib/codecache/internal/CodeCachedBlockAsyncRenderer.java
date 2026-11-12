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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.EntityType;
import org.xwiki.model.ModelContext;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.rendering.RenderingException;
import org.xwiki.rendering.async.internal.block.AbstractBlockAsyncRenderer;
import org.xwiki.rendering.async.internal.block.BlockAsyncRendererResult;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.CompositeBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.listener.MetaData;
import org.xwiki.rendering.macro.MacroExecutionException;
import org.xwiki.rendering.macro.code.CodeMacroParameters;
import org.xwiki.rendering.macro.code.source.CodeMacroSource;
import org.xwiki.rendering.macro.code.source.CodeMacroSourceFactory;
import org.xwiki.rendering.macro.source.MacroContentSourceReference;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.transformation.MacroTransformationContext;

/**
 * Executes the standard code macro through the async rendering framework, so that its result is cached and,
 * optionally, computed asynchronously.
 *
 * @version $Id$
 * @since 1.0
 */
@Component(roles = CodeCachedBlockAsyncRenderer.class)
public class CodeCachedBlockAsyncRenderer extends AbstractBlockAsyncRenderer
{
    private static final int MAX_ATTEMPTS = 3;

    @Inject
    private CodeMacroSourceFactory sourceFactory;

    @Inject
    private ModelContext modelContext;

    @Inject
    private CodeCachedBaseReferenceResolver baseReferenceResolver;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    private List<String> id;

    private String baseReference;

    private DocumentReference contextDocumentReference;

    private boolean inline;

    private Syntax targetSyntax;

    private CodeCachedMacro codeMacro;

    private CodeMacroParameters parameters;

    private String content;

    private MacroTransformationContext context;

    private boolean async;

    void initialize(CodeCachedMacro codeMacro, CodeMacroParameters parameters, String content,
        MacroTransformationContext context, boolean async) throws MacroExecutionException
    {
        this.codeMacro = codeMacro;
        // The transformation keeps modifying its context (current macro block, inline mode, etc.) while it executes
        // the next macros, so keep a copy of it for when the code macro is executed later on, asynchronously.
        this.context = context.clone();
        this.async = async;

        this.inline = context.isInline();
        this.targetSyntax = context.getTransformationContext().getTargetSyntax();

        // When the content comes from somewhere else (a script variable, a page, an attachment, etc.), resolve it now,
        // in the context of the current request (the script context, the current user's rights, the current macro
        // block, etc.): the asynchronous execution happens in a different context, where it could fail or resolve
        // another content. The code macro is then called with the resolved content instead of the source.
        MacroContentSourceReference sourceReference = parameters.getSource();
        if (sourceReference != null) {
            CodeMacroSource source = this.sourceFactory.getContent(sourceReference, context);
            this.content = source.getContent();
            this.parameters = copyWithoutSource(parameters, source.getLanguage());
        } else {
            this.content = content;
            this.parameters = parameters;
        }

        // The highlighting result only depends on the content to highlight and on the macro parameters, so they make
        // the cache id. Neither the document containing the macro nor the position of the macro in it can be used:
        // the content can be generated by a script, and the position depends on the result of the macros executed
        // before this one.
        // The exception is the references found in the title and image parameters (links, attachments, images): they
        // are relative to the document containing the macro. When the result is computed asynchronously, it's
        // rendered outside of that document, so remember it to render them, and make it part of the cache id. The title
        // and the image are not displayed in inline mode.
        if (!this.inline
            && (StringUtils.isNotEmpty(this.parameters.getTitle()) || this.parameters.getImage() != null)) {
            this.baseReference = this.baseReferenceResolver.resolve(context);
            // Some URLs generated for these references depend on the context document (e.g. the parent of the page to
            // create for a link to a missing page). It's not part of the cache id: only the first, asynchronous,
            // rendering produces URLs, the cached blocks being rendered later on in the context of each request.
            this.contextDocumentReference = this.documentAccessBridge.getCurrentDocumentReference();
        }
        this.id = createId("rendering", "macro", "code", computeHash(context));
    }

    private CodeMacroParameters copyWithoutSource(CodeMacroParameters parameters, String sourceLanguage)
    {
        CodeMacroParameters copy = new CodeMacroParameters();
        copy.setLanguage(parameters.getLanguage() != null ? parameters.getLanguage() : sourceLanguage);
        copy.setLayout(parameters.getLayout());
        copy.setTitle(parameters.getTitle());
        copy.setBlockTitle(parameters.getBlockTitle());
        copy.setImage(parameters.getImage());
        copy.setCssClass(parameters.getCssClass());
        copy.setWidth(parameters.getWidth());
        return copy;
    }

    private String computeHash(MacroTransformationContext macroContext)
    {
        StringBuilder key = new StringBuilder();
        append(key, this.content);
        append(key, this.parameters.getLanguage());
        append(key, this.parameters.getLayout());
        append(key, this.parameters.getTitle());
        append(key, this.parameters.getImage());
        append(key, this.parameters.getCssClass());
        append(key, this.parameters.getWidth());
        append(key, this.inline);
        append(key, this.targetSyntax != null ? this.targetSyntax.toIdString() : null);
        // The title is parsed with the syntax of the macro.
        append(key, macroContext.getSyntax() != null ? macroContext.getSyntax().toIdString() : null);
        // The highlighting parsers are components, which can be installed for a single wiki.
        append(key, getCurrentWiki());
        append(key, this.baseReference);

        // The key is encoded in UTF-8.
        return Base64.encodeBase64URLSafeString(DigestUtils.sha256(key.toString()));
    }

    private String getCurrentWiki()
    {
        EntityReference currentReference = this.modelContext.getCurrentEntityReference();
        EntityReference wikiReference =
            currentReference != null ? currentReference.extractReference(EntityType.WIKI) : null;
        return wikiReference != null ? wikiReference.getName() : null;
    }

    private void append(StringBuilder key, Object value)
    {
        // Prefix each value with its length so that different values can never produce the same key.
        if (value == null) {
            key.append("-1:");
        } else {
            String stringValue = value.toString();
            key.append(stringValue.length()).append(':').append(stringValue);
        }
    }

    @Override
    public BlockAsyncRendererResult render(boolean async, boolean cached) throws RenderingException
    {
        if (!async || this.contextDocumentReference == null) {
            return super.render(async, cached);
        }

        // The asynchronous execution happens outside of the document containing the macro, so make it the context
        // document while the result is computed and rendered. Only the context document changes (not the current
        // user), and nothing is executed in it: the title is only parsed and rendered. Loading that document makes the
        // cached result depend on it, so it's computed again when the document is modified.
        Map<String, Object> backup = new HashMap<>();
        try {
            this.documentAccessBridge.pushDocumentInContext(backup, this.contextDocumentReference);
        } catch (Exception e) {
            throw new RenderingException(
                String.format("Failed to set the context document [%s]", this.contextDocumentReference), e);
        }
        try {
            return super.render(async, cached);
        } finally {
            this.documentAccessBridge.popDocumentFromContext(backup);
        }
    }

    @Override
    protected Block execute(boolean async, boolean cached) throws RenderingException
    {
        Exception lastException = null;
        // Note: method only called when the content is not cached.
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            try {
                List<Block> result = this.codeMacro.executeCodeMacro(this.parameters, this.content, this.context);
                if (this.baseReference != null) {
                    // Resolve the title and image references relative to the document containing the macro, even
                    // when the result is rendered asynchronously, outside of that document.
                    result = Collections.singletonList(new MetaDataBlock(result, MetaData.BASE, this.baseReference));
                }
                return new CompositeBlock(result);
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
}
