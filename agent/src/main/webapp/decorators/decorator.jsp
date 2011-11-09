<%--

    Copyright (C) 2010-2011 LShift Ltd.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

            http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

--%>

<%@ taglib uri="http://www.opensymphony.com/sitemesh/decorator" prefix="decorator" %>

<!-- This decorator could be a static HTML file if we didn't need to the context lookup -->
<html>
  <head>
    <script type="text/javascript">
      <%
        // Allow the API root to overriden via the environment

        javax.naming.Context ctx = new javax.naming.InitialContext();
        javax.naming.Context myenv = (javax.naming.Context) ctx.lookup("java:comp/env");
        java.lang.String customRoot = (java.lang.String) myenv.lookup("diffaCustomRoot");

        ServletContext servletCtx = pageContext.getServletContext();
        String ctxPath = servletCtx.getContextPath();

        String apiRoot;
        if (customRoot.equals("__context__")) {
          apiRoot = ctxPath;
        } else {
          apiRoot = customRoot;
        }
      %>
      var API_BASE = "<%= apiRoot %>/rest"
    </script>

    <title><decorator:title default="Diffa"/></title>

    <link rel="stylesheet" href="css/blueprint/screen.css" type="text/css" media="screen, projection">
    <link rel="stylesheet" href="css/blueprint/print.css" type="text/css" media="print">
    <link rel="stylesheet" href="css/blueprint/ie.css" type="text/css" media="screen, projection">

    <link rel="stylesheet/less" type="text/css" href="less/styling.less"/>
    <script src="js/thirdparty/less-1.1.3.min.js"></script>

    <decorator:head/>

  </head>

  <body>
    <div class="container">
      <decorator:body/>
      <div class="footer">Diffa ${project.version} &copy;2010-2011 LShift Ltd.</div>
    </div>
  </body>

</html>
