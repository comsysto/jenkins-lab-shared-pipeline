# How to setup local environment

### Create a VM to simulate deployment server




### Running Jenkins locally in docker container

You can use the official Jenkins docker container.
Inject the hostname and username of the server you want to deploy to as environment variable.

```
docker run -p 8080:8080 -p 50000:50000 -v $(pwd)/jenkins_home:/var/jenkins_home jenkins/jenkins:lts
```

Jenkins is now available at http://localhost:8080


### Setup Jenkins