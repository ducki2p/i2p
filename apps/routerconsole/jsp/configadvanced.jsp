<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config advanced")%>
</head><body>

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigAdvancedHelper" id="advancedhelper" scope="request" />
<jsp:setProperty name="advancedhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />

<h1><%=intl._("I2P Advanced Configuration")%></h1>
<div class="main" id="main">

 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigAdvancedHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 <div class="configure">
 <div class="wideload">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <input type="hidden" name="action" value="blah" >
 <h3><%=intl._("Advanced I2P Configuration")%></h3>
 <textarea rows="32" cols="60" name="config" wrap="off" spellcheck="false"><jsp:getProperty name="advancedhelper" property="settings" /></textarea><br><hr>
      <div class="formaction">
        <input type="reset" value="<%=intl._("Cancel")%>" >
        <input type="submit" name="shouldsave" value="<%=intl._("Save changes")%>" >
 <br><b><%=intl._("NOTE")%>:</b> <%=intl._("Some changes may require a restart to take effect.")%>
 </div></form></div></div></div></body></html>
