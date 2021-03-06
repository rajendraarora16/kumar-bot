
package ai.kumar.server.api.service;

import org.json.JSONObject;

import ai.kumar.EmailHandler;
import ai.kumar.json.JsonObjectWithDefault;
import ai.kumar.server.APIException;
import ai.kumar.server.APIHandler;
import ai.kumar.server.AbstractAPIHandler;
import ai.kumar.server.Authorization;
import ai.kumar.server.BaseUserRole;
import ai.kumar.server.Query;
import ai.kumar.server.ServiceResponse;

import javax.servlet.http.HttpServletResponse;

/**
 * send an email in the name of the registered user
 * example:
 * http://127.0.0.1:4000/service/emailsender.json?mail=you@xyz.abc|test|123
 *
 */
public class EmailSenderService extends AbstractAPIHandler implements APIHandler {
   
    private static final long serialVersionUID = 857847830309879111L;

    @Override
    public BaseUserRole getMinimalBaseUserRole() { return BaseUserRole.ANONYMOUS; }

    @Override
    public JSONObject getDefaultPermissions(BaseUserRole baseUserRole) {
        return null;
    }

    public String getAPIPath() {
        return "/service/emailsender.json";
    }
    
    @Override
    public ServiceResponse serviceImpl(Query post, HttpServletResponse response, Authorization user, final JsonObjectWithDefault permissions) throws APIException {
        JSONObject json = new JSONObject(true).put("accepted", true);
        
        // unauthenticated users are rejected
        if (user.getIdentity().isAnonymous()) {
            json.put("accepted", false);
            json.put("reject-reason", "you must be logged in");
            return new ServiceResponse(json);
        }
        
        String mail = post.get("mail", "");
        
        if (mail.length() == 0) {
            json.put("accepted", false);
            json.put("reject-reason", "a mail attribute is required");
            return new ServiceResponse(json);
        }
        
        String[] m = mail.split("\\|");
        if (m.length != 3) {
            json.put("accepted", false);
            json.put("reject-reason", "the mail attribute must contain three parts (receiver, subject, text) separated by a | symbol");
            return new ServiceResponse(json);
        }
        
        // thats it, send the email
        String sender = user.getIdentity().getName();
        String addressTo = m[0];
        String subject = m[1];
        String text = m[2];
        
        try {
            EmailHandler.sendEmail(sender, sender, addressTo, subject, text);
        } catch (Exception e) {
            json.put("accepted", false);
            json.put("reject-reason", "cannot send mail: " + e.getMessage());
            return new ServiceResponse(json);
        }
        
        // success
        return new ServiceResponse(json);
    }
    
}
