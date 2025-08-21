package me.joshuakfarrar.apollo.auth

trait HasId[U, I]:
  def id(user: U): I