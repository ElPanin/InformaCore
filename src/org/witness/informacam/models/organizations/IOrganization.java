package org.witness.informacam.models.organizations;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.witness.informacam.InformaCam;
import org.witness.informacam.models.Model;
import org.witness.informacam.models.forms.IForm;

public class IOrganization extends Model implements Serializable {
	private static final long serialVersionUID = 5005603473230382669L;
	
	public String organizationName = null;
	public String organizationDetails = null;
	public String publicKey = null;
	public String organizationFingerprint = null;
	public String organizationIcon = null;
	public List<IRepository> repositories = new ArrayList<IRepository>();
	public List<IForm> forms = new ArrayList<IForm>();
	
	public IOrganization() {
		super();
	}
	
	public IOrganization(JSONObject organization) {
		super();
		inflate(organization);
	}
	
	public List<String> getFormNamespaces() {
		List<String> formNamespaces = null;
		
		for(IForm f : forms) {
			if(formNamespaces == null) {
				formNamespaces = new ArrayList<String>();
			}
			
			formNamespaces.add(f.namespace);
		}
		
		return formNamespaces;
	}
	
	public void save() {
		InformaCam informaCam = InformaCam.getInstance();
		informaCam.installedOrganizations.getByFingerprint(organizationFingerprint).inflate(this);
		informaCam.installedOrganizations.save();
	}
}
