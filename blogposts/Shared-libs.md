# TODO Titel
# TODO Abstract (1-2 Sätze)

---


# Einleitung

Wer Jenkins als Continuous Integration Server verwendet, ist vermutlich längst auf Jenkins Pipelines umgestiegen oder plant eine Migration zu Pipeline as Code in naher Zukunft. Gerade im Zeitalter von Microservices aber auch bei 'klassischen' Architekturen besteht zu entwickelnde System häufig aus mehreren Projekten, die alle eine eigene Delivery Pipeline in Jenkins benötigen. Man wird schnell feststellen, das in den verschiedenen Pipelines sehr ähnliche Aufgaben anfallen. Daher ist es sinnvoll die Funktionalität zentral für alle Projekte bereitzustellen. 

Jenkins bietet dafür eine einfache und sauberer Lösung: Shared Libaries. In diesem Blogpost stellen wir anhand eines Beispiels vor, wie Pipeline Code extrahiert und wiederverwendet werden kann. Danach gehen wir noch einen Schritt weiter und zeigen wie auf Basis der Shared Libaries, durch Erweiterung der Jenkinsfile DSL, eine kurze und prägnate Deklaration von Pipelines entstehen kann.

Wer sich zuerst in die Grundlagen von Jenkinsfiles einlesen möchte, dem sei der Blogpost
[Saubere CI-Builds mit Jenkins Pipeline Jobs ](https://comsysto.com/blog-post/saubere-ci-builds-mit-jenkins-pipeline-jobs-docker-und-blue-ocean) auf unsere Webseite und die offizielle Jenkins [Pipeline Dokumention](https://jenkins.io/doc/book/pipeline/) empfohlen.


# Ausgangsituation

* 2 nahezu identische Spring Boot Apps

* 2 abweichende Pipeline Definitionen die mehr oder weniger die selbe Funktionalität haben (Checkout -> Build -> Deploy -> Healthcheck)

Jenkinsfile Service 1:

~~~groovy
node {
	try {
		stage('Checkout') {
			echo 'Checkout'
			checkout scm
			sh 'git clean -dfx'
		}

		dir('service-1') {	
			stage('Build') {
				env.PATH = "${tool 'gradle'}/bin:${env.PATH}"
				sh 'gradle build'
			}
			
			stage('Deploy') {
				httpRequest url: 'http://jenkinslab-deployserver:9090/shutdown', httpMode: 'POST', validResponseCodes: '200,408'
				sh 'scp build/libs/service-1-0.0.1-SNAPSHOT.jar jenkins@jenkinslab-deployserver:~/jenkinslab/service-1/'
				sh 'ssh matthias@jenkinslab-deployserver "nohup java -jar jenkinslab/service-1/service-1-0.0.1-SNAPSHOT.jar --server.port=9090" &'
			}
			
			stage('Healthcheck') {
				retry(5) {
					sleep time: 10, units: 'SECONDS'
					httpRequest url: 'http://jenkinslab-deployserver:9090/health', validResponseContent: '"status":"UP"'
				}
			}
		}
	}
	catch (exc) {
		echo "Caught: ${exc}"
		currentBuild.result = 'FAILURE'
	}
}
~~~

Jenkinsfile Service 2:

~~~groovy
node {
    try {
        stage('Checkout') {
            git url: 'https://github.com/comsysto/jenkins-lab-shared-pipeline.git', credentialsId: 'GITHUB_CRED'
        }

        dir('service-2') {

            stage('Build') {
                sh './gradlew clean build'
            }

            stage('Deploy') {
                sshagent(credentials: ['VAGRANT-SSH']) {
                    sh 'ssh -o StrictHostKeyChecking=no vagrant@192.168.33.10  mkdir -p service-2'
                    sh 'ssh -o StrictHostKeyChecking=no vagrant@192.168.33.10 ./service-2/shutdown.sh || true'
                    sh 'scp -o StrictHostKeyChecking=no build/libs/service-2-0.0.1-SNAPSHOT.jar startup.sh shutdown.sh vagrant@192.168.33.10:~/service-2/'
                    sh 'ssh -o StrictHostKeyChecking=no agrant@192.168.33.10 chmod -R 755 /service-2'
                    sh 'ssh -o StrictHostKeyChecking=no vagrant@192.168.33.10 ./service-2/startup.sh &'
                }
            }
        }
    } catch (exc) {
        echo "Caught: ${exc}"
        currentBuild.result = 'FAILURE'
    }
}
~~~

* Die einzelnen Stages habe zwar den selben Zweck sind allerdings auf unterschiedliche Weise implementiert worden.
* Verstoß gegen das DRY-Prinzip (Don't Repeat Yourself) 
* Problem: Wiederverwendbarkeit
* Problem: Schwer zu warten


# Schritt 1 - Extrahieren von Funktionen (Jenkins Shared Libraries)

Abhilfe verschaffen durch extrahieren einzelner Funktionen, die als [Shared Libary](https://jenkins.io/doc/book/pipeline/shared-libraries/) in Jenkins zur Verfügung gestellt werden.

Dazu erstellt man ein eigenes Repository indem der geteilte Pipeline Code abgelegt wird. Dieses Repository wird nun in Jenkins unter *Jenkins > Jenkins verwalten > System konfigurieren* im Abschnitt "Global Pipeline Libraries" konfiguriert.


* TODO Screenshot: Einrichten von Shared Libraries und Ordnerstruktur 



* Bspl. Code , z.B. deploy.groovy + Jenkinsfile snippet

/vars/deploy.groovy

~~~groovy
def call(String filename, String user, String server, String port, String deploymentPath = '~/deployment/') {
    httpRequest url: "http://${server}:${port}/shutdown", httpMode: 'POST', validResponseCodes: '200,408,404'
	sh "ssh ${user}@${server} \"mkdir -p ${deploymentPath}\""
	sh "scp build/libs/${filename} ${user}@${server}:${deploymentPath}"
	sh "ssh ${user}@${server} \"nohup java -jar ${deploymentPath}/${filename} --server.port=${port}\" &"
}
~~~

Dabei ist darauf zu achten, dass das Groovy Skript im Ordner `vars/` abgelegt wird und die Datei selbst mit camelCase Syntax benannt ist. 


In unserem Beispiel haben wir uns für implizites Laden der Library entschieden. Somit muss im Jenkinsfile selbst nicht nochmals die Library explizit geladen werden. Im Jenkinsfile kann die *Deploy* Stage nun wiefolgt vereinfacht und vereinheiltlicht werden:

~~~groovy
stage('Deploy') {
    sshagent(credentials: [jenkinsSshCredentialsId]) {
        deploy(jarName, 'vagrant', serverHostname, serverPort, '~/')
    }
}
~~~

Analog können weitere Teile der Pipeline wie z.B. *Checkout*, *Build* & *Healthcheck* als eigene Funktionen ausgelagert werden.

# Schritt 2 - Vereinfachen der Pipeline mit Hilfe einer strukturierten DSL 

>If you have a lot of Pipelines that are mostly similar, the global variable mechanism provides a handy tool to build a higher-level DSL that captures the similarity. For example, all Jenkins plugins are built and tested in the same way, so we might write a step named buildPlugin:

/vars/springBootPipeline.groovy

~~~groovy
def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    node {
        try {
            stage('Checkout') {
                checkout scm
                sh 'git clean -dfx'
            }

            dir(config.servicename) {
                stage('Build') {
                    sh './gradlew clean build'
                }

                stage('Deploy') {
                    deploy(config.filename, config.username, config.hostname, config.port)
                }

                stage('Healthcheck') {
                    healthcheck("http://${config.hostname}:${config.port}/health")
                }
            }
        }
        catch (exc) {
            echo "Caught: ${exc}"
            currentBuild.result = 'FAILURE'
        }
    }
}
~~~

Verwendung / Jenkinsfile

~~~groovy
springBootPipeline {
    hostname = env.DEPLOYMENT_SERVER_HOSTNAME
    username = env.DEPLOYMENT_USER
    port = '9092'
    filename = 'service-2-0.0.1-SNAPSHOT.jar'
    servicename = 'service-2'
}
~~~


# Allgemeine Punkte

* Versioning + Explizites Laden
* Hinweis: In der Praxis filename nicht fix 

# Fazit

* Super einfach
* DRY


# Referenzen
* github repo
* siehe Trello Karte


