//usr/bin/env jbang "$0" "$@" ; exit $?
//REPOS mavencentral,atlassian=https://packages.atlassian.com/maven/repository/public
//DEPS info.picocli:picocli:4.2.0, com.atlassian.jira:jira-rest-java-client-api:3.0.0, com.atlassian.jira:jira-rest-java-client-core:3.0.0, org.json:json:20200518, com.konghq:unirest-java:3.7.04, com.sun.mail:javax.mail:1.6.2

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.*;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import com.sun.mail.smtp.SMTPTransport;
import org.json.JSONArray;
import org.json.JSONObject;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import javax.activation.DataHandler;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;

import javax.activation.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

@Command(name = "run", mixinStandardHelpOptions = true, version = "run 0.1",
        description = "JIRA Lint")
class run implements Callable<Integer> {

    @CommandLine.Option(names = {"-u", "--username"}, description = "The username to use when connecting to the JIRA server", required = true)
    private String jiraUsername;

    @CommandLine.Option(names = {"-p", "--password"}, description = "The password to use when connecting to the JIRA server", required = true)
    private String jiraPassword;

    @CommandLine.Option(names = {"-s", "--jira-server"}, description = "The JIRA server to connect to", required = true)
    private String jiraServerURL;

    @CommandLine.Option(names = {"-r", "--config"}, description = "The config file to load the query to version mappings from", required = true)
    private String pathToConfigFile;

    private static final String SMTP_SERVER = "smtp.corp.redhat.com";
    private static final String USERNAME = "";
    private static final String PASSWORD = "";
    private static final String EMAIL_FROM = "probinso@redhat.com";
    private static final String EMAIL_SUBJECT = "ACTION REQUIRED: Please update these Quarkus JIRA issues";

    //Cache of component leads (looking up is expensive)
    private static final Map<String, JiraUser> componentToLeadMap = new HashMap<>();

    private JiraRestClient restClient;

    public static void main(String... args) {
        int exitCode = new CommandLine(new run()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {

        /*
            Initialise
         */
        Map<String, CheckItem> configuration = loadCheckItemMap(pathToConfigFile);
        restClient = new AsynchronousJiraRestClientFactory().createWithBasicHttpAuthentication(new URI(jiraServerURL), jiraUsername, jiraPassword);

        /*
            Gather all report results
         */
        List<ReportResult> allReportResults = new ArrayList<>();
        for (String checkItemID : configuration.keySet()) {

            CheckItem checkItem = configuration.get(checkItemID);
            SearchResult searchResultsAll = restClient.getSearchClient().searchJql(checkItem.getJql()).claim();

            for (Issue issue :searchResultsAll.getIssues()) {

                JiraUser jiraUser;
                if (issue.getAssignee() != null) { // Try assignee first
                    jiraUser = new JiraUser(issue.getAssignee().getDisplayName(), issue.getAssignee().getEmailAddress());
                }
                else if (getFirstComponentLead(issue) != null) { // Try component lead next
                    jiraUser = getFirstComponentLead(issue);
                }
                else //Fallback to JIRA Lint process maintainer
                {
                    jiraUser = new JiraUser("Paul Robinson", "probinso@redhat.com");
                }

                ReportResult reportResult = new ReportResult(issue.getSummary(), checkItem.getDescription(), new URL("https://issues.redhat.com/browse/" + issue.getKey()), issue.getKey(), jiraUser);
                allReportResults.add(reportResult);
            }
        }

        /*
            Assign each report Result to a user
         */
        Map<JiraUser, List<ReportResult>> reportsByJiraUser = new HashMap<>();
        for(ReportResult reportResult : allReportResults) {

            if (reportsByJiraUser.get(reportResult.getContact()) == null) {
                reportsByJiraUser.put(reportResult.getContact(), new ArrayList<>());
            }
            reportsByJiraUser.get(reportResult.getContact()).add(reportResult);
        }

        /*
            Generate Email for each user
         */
        for (JiraUser jiraUser : reportsByJiraUser.keySet()) {
            String emailBody = createEmailBody(jiraUser, reportsByJiraUser.get(jiraUser));
            sendMail(emailBody, "probinso@redhat.com"); //Always send emails to Paul for now
        }

        return 0;
    }

    private JiraUser lookupComponentLead(String componentName) {

        //Return cached value if available
        if (componentToLeadMap.get(componentName) != null) {
            return componentToLeadMap.get(componentName);
        }

        //Not cached, so lookup
        JiraUser result;
        Project project = restClient.getProjectClient().getProject("QUARKUS").claim();
        for (BasicComponent basicComponent : project.getComponents()) {
            if (basicComponent.getName().equals(componentName)) {
               Component fullComponent =  restClient.getComponentClient().getComponent(basicComponent.getSelf()).claim();
               if (fullComponent.getLead() != null) {
                   User user = restClient.getUserClient().getUser(fullComponent.getLead().getSelf()).claim();
                   result = new JiraUser(user.getDisplayName(), user.getEmailAddress());
                   componentToLeadMap.put(fullComponent.getName(), result);
                   return result;
               }
            }
        }

        //Failed to find one, so return null
        return null;
    }

    private JiraUser getFirstComponentLead(Issue issue) {
        for (BasicComponent component : issue.getComponents()) {
            JiraUser componentLead = lookupComponentLead(component.getName());
            if (componentLead != null) {
                return componentLead;
            }
        }
        return null;
    }

    private Map<String, CheckItem> loadCheckItemMap(String pathToConfigFile) {
        try {

            String jsonString = new String(Files.readAllBytes(Paths.get(pathToConfigFile)));
            JSONArray jsonArray = new JSONArray(jsonString);

            Map<String, CheckItem> checkItemsMap = new HashMap<>();
            for (int i=0; i<jsonArray.length(); i++) {
                JSONObject checkItemJson = jsonArray.getJSONObject(i);

                if (checkItemJson.length() != 1)
                    throw new RuntimeException("Unexpected number of items found: " + checkItemJson.length() + ". This message needs to be more specific! sorry :-(");

                String checkItemID = checkItemJson.keys().next();
                JSONObject checkItemBody = checkItemJson.getJSONObject(checkItemID);

                CheckItem checkItem = new CheckItem(checkItemBody.getString("jql"), checkItemBody.getString("description"));
                checkItemsMap.put(checkItemID, checkItem);
            }

            return checkItemsMap;
        } catch (IOException e) {
            throw new RuntimeException("Error loading " + pathToConfigFile, e);
        }
    }

    public String createEmailBody(JiraUser jiraUser, List<ReportResult> reportResults) {

        System.out.println("Sending email for user: " + jiraUser.getName());

        String body = "<p>Hi " + jiraUser.getName() + ",</p>" +
                "<p>The following JIRA issues listed in this email are missing some information and need fixing.</P>" +
                "<p>The JIRA Lint tool has determined that you are the best person to ask to fix them. Most likely you are the assignee, or (for issues with no assignee) the component lead.</p>";

        body += "<table border='1' style='border-collapse:collapse'>";
        body += "<tr><th>Issue Link</th><th>Issue Summary</th><th>Problem Description</th></tr>";
        for (ReportResult reportResult : reportResults) {

            body += "<tr>";
            body +=     "<td><a href='" +  reportResult.getJiraLink() + "'>" + reportResult.getJiraKey() + "</a></td>";
            body +=     "<td>" + reportResult.getSummary() + "</td>";
            body +=     "<td>" + reportResult.getDescription() + "</td>";
            body += "</tr>";
        }
        body += "</table>";

        body += "</p>";
        body += "<p>Thanks,</p>";
        body += "<p>Paul (via the JIRA Lint tool)</p>";

        return body;
    }

    public static void sendMail(String body, String to) {

        Properties prop = System.getProperties();
        prop.put("mail.smtp.auth", "false");

        Session session = Session.getInstance(prop, null);
        Message msg = new MimeMessage(session);

        try {

            msg.setFrom(new InternetAddress(EMAIL_FROM));

            msg.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(to, false));

            msg.setSubject(EMAIL_SUBJECT);

            // TEXT email
            //msg.setText(EMAIL_TEXT);

            // HTML email
            msg.setDataHandler(new DataHandler(new HTMLDataSource(body)));

            SMTPTransport t = (SMTPTransport) session.getTransport("smtp");

            // connect
            t.connect(SMTP_SERVER, USERNAME, PASSWORD);

            // send
            t.sendMessage(msg, msg.getAllRecipients());

            System.out.println("Response: " + t.getLastServerResponse());

            t.close();

        } catch (MessagingException e) {
            e.printStackTrace();
        }

    }



    static class HTMLDataSource implements DataSource {

        private String html;

        public HTMLDataSource(String htmlString) {
            html = htmlString;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (html == null) throw new IOException("html message is null!");
            return new ByteArrayInputStream(html.getBytes());
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new IOException("This DataHandler cannot write HTML");
        }

        @Override
        public String getContentType() {
            return "text/html";
        }

        @Override
        public String getName() {
            return "HTMLDataSource";
        }
    }

    class ReportResult {

        String summary;
        String description;
        URL jiraLink;
        String jiraKey;
        JiraUser contact;

        public ReportResult(String summary, String description, URL jiraLink, String jiraKey, JiraUser contact) {
            this.summary = summary;
            this.description = description;
            this.jiraLink = jiraLink;
            this.jiraKey = jiraKey;
            this.contact = contact;
        }

        public String getSummary() {
            return summary;
        }

        public String getDescription() {
            return description;
        }

        public URL getJiraLink() {
            return jiraLink;
        }

        public JiraUser getContact() {
            return contact;
        }

        public String getJiraKey() {
            return jiraKey;
        }
    }

    class JiraUser {

        private String name;
        private String email;

        public JiraUser(String name, String email) {
            this.name = name;
            this.email = email;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            JiraUser jiraUser = (JiraUser) o;
            return Objects.equals(name, jiraUser.name) && Objects.equals(email, jiraUser.email);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, email);
        }
    }

    class CheckItem {

        private String jql;
        private String description;

        public CheckItem(String jql, String description) {
            this.jql = jql;
            this.description = description;
        }

        public String getJql() {
            return jql;
        }

        public String getDescription() {
            return description;
        }
    }
}
