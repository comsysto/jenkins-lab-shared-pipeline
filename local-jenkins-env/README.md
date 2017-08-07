# How to setup local environment

### Create a VM to simulate deployment server

You can use a standard Ubuntu image, and start it in VirtualBox. After stgartup you need to install Java on the VM.


### Running Jenkins locally in docker container

You can use the official Jenkins docker container.

```
docker run -p 8080:8080 -p 50000:50000 -v $(pwd)/jenkins_home:/var/jenkins_home --name jenkins jenkins/jenkins:lts
```

Jenkins is now available at http://localhost:8080


### Setup Jenkins

* Plugins

You can start with the recommended plugins. Additionally installation of HTTP Request Plugin is required.

* Environment variables

The hostname and username of the server you want to deploy your applications have to be defined as environment variables in Jenkins. ('Jenkins verwalten' -> System konfigurieren' -> 'Globale Eigenschaften' -> 'Umgebungsvariablen'). The environment variables are called 'DEPLOYMENT_SERVER_HOSTNAME' and 'DEPLOYMENT_USER'.

* Pipeline libaries

The pipeline libraries have to be defined globally in Jenkins. ('Jenkins verwalten' -> System konfigurieren' -> 'Global Pipeline Libraries')
Add a library with the following  properties:

	- Name: 'jenkinslab'
	- Default version: 'master'
	- Check 'Load implicitly'
	- Check 'Allow default version to be overridden'
	- GitHub Owner: comsysto
	- Repository: jenkins-lab-shared-library
	
Make sure to use appropriate GitHub credentials.
The library is now globally available for all your pipelines.

* SSH keys

Jenkins needs to be able to access the deployment VM via SSH. To set this up you need to complete the following steps:

	- Go to your home directory on Jenkins
	- Call 'ssh-keygen' without entering a password
	- Copy the generated '~/.ssh/id_rsa.pub' to the deployment VM
	- Insert the copied public key to the file '~/.ssh/authorized_keys' on the deployment VM
	