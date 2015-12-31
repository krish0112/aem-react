package com.sinnerschrader.aem.react;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.servlet.RequestDispatcher;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.pool2.ObjectPool;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;
import org.apache.sling.commons.json.sling.JsonObjectCreator;
import org.apache.sling.scripting.api.AbstractSlingScriptEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.WCMMode;
import com.day.cq.wcm.api.components.ComponentContext;
import com.day.cq.wcm.api.components.EditContext;
import com.day.cq.wcm.api.components.IncludeOptions;
import com.day.cq.wcm.commons.WCMUtils;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.Template;
import com.sinnerschrader.aem.react.exception.TechnicalException;

public class ReactScriptEngine extends AbstractSlingScriptEngine {

  private static final String SERVER_RENDERING_DISABLED = "disabled";
  private static final String SERVER_RENDERING_PARAM = "serverRendering";
  private static final Logger LOG = LoggerFactory.getLogger(ReactScriptEngine.class);
  private ObjectPool<JavascriptEngine> enginePool;
  private boolean reloadScripts;
  private ObjectMapper mapper;

  protected ReactScriptEngine(ScriptEngineFactory scriptEngineFactory, ObjectPool<JavascriptEngine> enginePool, boolean reloadScripts) {
    super(scriptEngineFactory);

    this.mapper = new ObjectMapper();
    mapper.configure(Feature.IGNORE_UNKNOWN, true);

    this.enginePool = enginePool;
    this.reloadScripts = reloadScripts;

  }


  @Override
  public Object eval(Reader reader, ScriptContext context) throws ScriptException {
    ClassLoader old = Thread.currentThread().getContextClassLoader();
    try {

      Thread.currentThread().setContextClassLoader(((ReactScriptEngineFactory) getFactory()).getClassLoader());

      Bindings bindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
      SlingHttpServletRequest request = (SlingHttpServletRequest) bindings.get(SlingBindings.REQUEST);
      Resource resource = request.getResource();

      ReactComponentConfig config = parseReactComponentConfig(reader);

      if (config.isReload()) {
        // This is a react child component. The page needs to be rerendered
        // completely. In the future we might ask the parent to reload its
        // content via ajax.
        String script = "<script>AemGlobal.componentManager.reloadRootInCq('"+resource.getPath()+"')</script>";
        context.getWriter().write(script);
      } else {

        JSONObject reactProps = createReactProps(config, request, resource);

        String component = reactProps.getString("component");

        String renderedHtml;
        boolean serverRendering = !SERVER_RENDERING_DISABLED.equals(request.getParameter(SERVER_RENDERING_PARAM));
        if (serverRendering) {
          String reactMarkup = renderReactMarkup(reactProps, component);
          renderedHtml = postRender(reactMarkup, context);
        } else {
          renderedHtml = "";
        }
        String allHtml = wrapHtml(resource.getPath(),reactProps, component, renderedHtml, serverRendering);
        
        context.getWriter().write(allHtml);
      }
      return null;

    } catch (Exception e) {
      throw new ScriptException(e);
    } finally {
      Thread.currentThread().setContextClassLoader(old);
    }

  }
/**
 * wrap the rendered react markup with the teaxtarea that contains the component's props.
 * @param path
 * @param reactProps
 * @param component
 * @param renderedHtml
 * @param serverRendering
 * @return
 */
  private String wrapHtml(String path,JSONObject reactProps, String component, String renderedHtml, boolean serverRendering) {
    String allHtml = "<div data-react-server=\"" + String.valueOf(serverRendering) + "\" data-react=\"app\" data-react-id=\"" + path + "_component\">"
        + renderedHtml + "</div>" + "<textarea id=\"" + path + "_component\" style=\"display:none;\">" + reactProps.toString() + "</textarea>";
    allHtml+="<script>if (typeof AemGlobal!=='undefined') AemGlobal.componentManager.updateComponent(\""+path+"_component\")</script>";
    
    return allHtml;
  }
/**
 * render the react markup
 * @param reactProps props
 * @param component component name
 * @return
 */
  private String renderReactMarkup(JSONObject reactProps, String component) {
    JavascriptEngine javascriptEngine;
    try {
      javascriptEngine = enginePool.borrowObject();
      try {
        if (reloadScripts) {
          javascriptEngine.reloadScripts();
        }
        return javascriptEngine.render(component, reactProps.toString());
      } finally {
        enginePool.returnObject(javascriptEngine);
      }
    } catch (NoSuchElementException e) {
      throw new TechnicalException("cannot get engine from pool", e);
    } catch (IllegalStateException e) {
      throw new TechnicalException("cannot return engine from pool", e);
    } catch (Exception e) {
      throw new TechnicalException("error rendering react markup", e);
    }

  }

  private ReactComponentConfig parseReactComponentConfig(Reader reader) {
    try {
      return mapper.readValue(reader, ReactComponentConfig.class);
    } catch (IOException e) {
      throw new TechnicalException("react component config is invalid", e);
    }
  }

  private JSONObject createReactProps(ReactComponentConfig config, SlingHttpServletRequest request, Resource resource) {
    try {
      int depth = config.getDepth();
      JSONObject resourceAsJson = JsonObjectCreator.create(resource, depth);
      JSONObject reactProps = new JSONObject();
      reactProps.put("resource", resourceAsJson);
      reactProps.put("component", config.getComponent());
      reactProps.put("depth", config.getDepth());
      reactProps.put("wcmmode", getWcmMode(request));
      reactProps.put("path", resource.getPath());
      return reactProps;
    } catch (JSONException e) {
      throw new TechnicalException("cannot create react props", e);
    }

  }


  private String getWcmMode(SlingHttpServletRequest request) {
    return WCMMode.fromRequest(request).name().toLowerCase();
  }
/**
 * parse the rendered html and render it as handlebars template. The only relevant dynamic part will be the helper 
 * that includes a given resource
 * <pre><code>{{{include-resource path resourceType}}}}</code></pre>
 * @param html
 * @param context
 * @return
 */
  private String postRender(String html, final ScriptContext context) {
    Template template;
    try {
      // TODO initialize helper in engine constructor - request needs to be made
      // available to helper.
      Handlebars handlebars = new Handlebars();
      handlebars.registerHelper("include-resource", new Helper<String>() {

        @Override
        public CharSequence apply(String path, Options options) throws IOException {
          String resourceType = options.param(0);
          StringWriter writer = new StringWriter();
          includeResource(new PrintWriter(writer), path, null, resourceType, context);
          return writer.toString();
        }
      });
      template = handlebars.compileInline(html);
    } catch (IOException e) {
      throw new TechnicalException("cannot compile template ", e);
    }
    Map<String, String> ctx = new HashMap<String, String>();
    try {
      return template.apply(ctx);
    } catch (IOException e) {
      throw new TechnicalException("cannot render template", e);
    }
  }

  /**
   * get an included resource and write it to the writer.
   * @param out
   * @param script really the path
   * @param dispatcherOptions
   * @param resourceType 
   * @param context
   */
  private void includeResource(PrintWriter out, String script, String dispatcherOptions, String resourceType, ScriptContext context) {
    
    
    Bindings bindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
    if (StringUtils.isEmpty(script)) {
      LOG.error("Script path cannot be empty");
    } else {
      SlingHttpServletResponse customResponse = new PrintWriterResponseWrapper(out, (SlingHttpServletResponse) bindings.get(SlingBindings.RESPONSE));
      SlingHttpServletRequest request = (SlingHttpServletRequest) bindings.get(SlingBindings.REQUEST);
      
      script = normalizePath(request, script);
      ComponentContext componentContext = WCMUtils.getComponentContext(request);
      EditContext editContext=componentContext.getEditContext();

      Resource includeRes = request.getResourceResolver().resolve(script);
      if (includeRes instanceof NonExistingResource || includeRes.isResourceType(Resource.RESOURCE_TYPE_NON_EXISTING)) {
        includeRes = new SyntheticResource(request.getResourceResolver(), script, resourceType);
      }
      try {
        RequestDispatcherOptions opts = new RequestDispatcherOptions(dispatcherOptions);
        if (StringUtils.isNotEmpty(resourceType)) {
          opts.setForceResourceType(resourceType);
        }
        SlingBindings more = (SlingBindings)request.getAttribute(SlingBindings.class.getName());
       // EditContext editContext=(EditContext) bindings.get("editContext");
        IncludeOptions options = IncludeOptions.getOptions(request, true);
        if (editContext==null) {
          // this is the editable.refresh() case where the root should not be decorated but all others.
          // TODO better move this code up to the eval method
          String[] selectors = request.getRequestPathInfo().getSelectors();
         //StringBuilder replaceSelectors = 
          options.forceEditContext(true);
          options.setDecorationTagName("");
          // TODO only remove editCOntext selector.
          opts.setReplaceSelectors("");
          //opts.setReplaceSelectors("");
          //ComponentContext componentContext=(ComponentContext) bindings.get("componentContext");
          //componentContext.setDecorate(false);
        }
        

        RequestDispatcher dispatcher = request.getRequestDispatcher(includeRes, opts);
        dispatcher.include(request, customResponse);
        //editContext.includeEpilog(request, customResponse, WCMMode.fromRequest(request));
      } catch (Exception e) {
        LOG.error("Failed to include resource {}", script, e);
      }
    }
  }

  private String normalizePath(SlingHttpServletRequest request, String path) {
    if (!path.startsWith("/")) {
      path = request.getResource().getPath() + "/" + path;
    }
    return ResourceUtil.normalize(path);
  }

  public void stop() {
    enginePool.close();
  }

}